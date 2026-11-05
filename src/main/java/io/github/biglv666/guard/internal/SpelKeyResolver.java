package io.github.biglv666.guard.internal;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.CompositeStringExpression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.CompoundExpression;
import org.springframework.expression.spel.ast.ConstructorReference;
import org.springframework.expression.spel.ast.Indexer;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.ast.PropertyOrFieldReference;
import org.springframework.expression.spel.ast.TypeReference;
import org.springframework.expression.spel.ast.VariableReference;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 注解 key 的 SpEL 解析器。
 *
 * <p>支持两种写法：
 * <ul>
 *   <li>纯表达式：{@code @Idempotent(key = "#orderId")}</li>
 *   <li>模板表达式：{@code @Idempotent(key = "order:#{#order.id}")}，便于在键里携带字面前缀</li>
 * </ul>
 *
 * <p>表达式按 {@link Method} 编译一次并缓存，求值使用只读数据绑定的
 * {@link SimpleEvaluationContext}（不暴露类型引用、静态方法、构造器等能力，防止表达式注入），
 * 上下文变量为方法参数名（要求编译期开启 {@code -parameters}，
 * spring-boot-starter-parent 默认开启），同时提供 {@code #p0}/{@code #a0} 位置别名与 {@code #root}。
 * 实例方法调用（如 {@code #order.getId()}）通过 {@code withInstanceMethods()} 开放，
 * 但仍不开放类型引用和静态方法。
 *
 * <p>除运行时解析外，本类还提供 {@link #validate(Method, String, PropertyCheckMode)}
 * 供启动期校验与业务方单测使用：可发现语法错误、变量名拼写错误、位置变量越界、
 * 类型引用/构造器、以及尽力而为的属性/方法名拼写问题。
 *
 * <p>与限流 starter 的可选后缀不同：幂等与锁的 key 是语义必需项，
 * 因此解析/求值失败一律快速失败（抛 {@link KeyResolveException}），
 * 避免静默回退到方法级 key 导致防护粒度意外变粗。
 *
 * @author Guard Team
 * @since 0.1.0
 */
public class SpelKeyResolver {

    private static final String[] EMPTY_NAMES = new String[0];

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 编译结果按 Method + 表达式缓存；值为 null 表示该注解未声明 key 表达式（方法级默认键）。
     */
    private final ConcurrentMap<CacheKey, Expression> expressionCache = new ConcurrentHashMap<>();

    /**
     * 方法参数名缓存：DefaultParameterNameDiscoverer 每次调用都会重新解析，启动校验和
     * 高频请求路径共用此缓存。
     */
    private final ConcurrentMap<Method, String[]> parameterNamesCache = new ConcurrentHashMap<>();

    /**
     * 解析注解 key 表达式。
     *
     * @param method        被拦截的方法
     * @param args          方法实际入参（与方法参数名按位置对应）
     * @param keyExpression 注解声明的 key 表达式；允许为 null 或空白，表示方法级默认键
     * @return 解析出的 key 值；keyExpression 为空白（未声明表达式）时返回 null
     * @throws KeyResolveException 表达式编译或求值失败时抛出（快速失败，不静默回退）；
     *                             表达式已声明但求值结果为 null 或空白时同样抛出，
     *                             避免 key 意外回退到方法级默认键导致防护粒度变粗
     */
    public String resolve(Method method, Object[] args, String keyExpression) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return null;
        }
        CacheKey cacheKey = new CacheKey(method, keyExpression);
        Expression expression = expressionCache.computeIfAbsent(cacheKey, this::compile);
        Object[] actualArgs = args != null ? args : new Object[0];
        try {
            SimpleEvaluationContext context = buildContext(method, actualArgs);
            Object value = expression.getValue(context);
            // 声明了表达式却求值出 null/空白（如入参业务 id 为 null）：属于数据或配置错误。
            // 若静默回退方法级默认键，会把"按业务 id 隔离"意外放大为"整个方法互斥"，造成无关请求被误拒，故快速失败。
            String resolved = value == null ? null : String.valueOf(value);
            if (resolved == null || resolved.isBlank()) {
                throw new KeyResolveException("key 表达式求值结果为空（null 或空白），已拒绝静默回退方法级默认键"
                        + " - method: " + method.getName() + ", key: '" + keyExpression + "'", null);
            }
            return resolved;
        } catch (KeyResolveException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyResolveException("key 表达式求值失败 - method: " + method.getName()
                    + ", key: '" + keyExpression + "'", e);
        }
    }

    /**
     * 求值布尔表达式，供 {@code @Idempotent(condition/unless)} 使用。
     *
     * @param method     被拦截的方法
     * @param args       方法入参
     * @param expression 布尔 SpEL；空白表达式返回 {@code defaultValue}
     * @param result     业务返回值，绑定为 {@code #result}；条件表达式可传 null
     * @param defaultValue 表达式为空白时的返回值
     * @return 表达式求值结果
     * @throws KeyResolveException 表达式编译或求值失败时抛出
     * @since 0.3.0
     */
    public boolean evaluateBoolean(Method method, Object[] args, String expression,
                                   Object result, boolean defaultValue) {
        if (expression == null || expression.isBlank()) {
            return defaultValue;
        }
        CacheKey cacheKey = new CacheKey(method, expression);
        Expression compiled = expressionCache.computeIfAbsent(cacheKey, this::compile);
        Object[] actualArgs = args != null ? args : new Object[0];
        try {
            SimpleEvaluationContext context = buildContext(method, actualArgs);
            context.setVariable("result", result);
            Object value = compiled.getValue(context);
            if (value == null) {
                return false;
            }
            if (value instanceof Boolean booleanValue) {
                return booleanValue;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        } catch (KeyResolveException e) {
            throw e;
        } catch (Exception e) {
            throw new KeyResolveException("条件表达式求值失败 - method: " + method.getName()
                    + ", expression: '" + expression + "'", e);
        }
    }

    /**
     * 构建求值上下文并绑定参数变量。
     */
    private SimpleEvaluationContext buildContext(Method method, Object[] actualArgs) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding()
                .withInstanceMethods()
                .withRootObject(actualArgs)
                .build();
        bindVariables(context, method, actualArgs);
        return context;
    }

    /**
     * 校验 key 表达式，供启动期扫描和业务方单元测试使用。
     *
     * @param method        被拦截的方法
     * @param keyExpression 注解声明的 key 表达式；空白表示使用方法级默认键，直接通过
     * @param mode          属性/方法名检查级别；null 按 {@link PropertyCheckMode#WARN} 处理
     * @return 错误与警告列表；语法错误会出现在 errors 中
     */
    public ValidationResult validate(Method method, String keyExpression, PropertyCheckMode mode) {
        return validate(method, keyExpression, mode, Set.of());
    }

    /**
     * 校验表达式，并允许额外变量（如 {@code #result} 用于 unless 表达式）。
     *
     * @param method         被拦截的方法
     * @param keyExpression  表达式
     * @param mode           属性/方法名检查级别
     * @param extraVariables 除参数名、#pN/#aN、#root/#this 外额外允许的变量
     * @return 校验结果
     * @since 0.3.0
     */
    public ValidationResult validate(Method method, String keyExpression, PropertyCheckMode mode,
                                    Set<String> extraVariables) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return ValidationResult.empty();
        }
        Expression expression;
        try {
            expression = expressionCache.computeIfAbsent(new CacheKey(method, keyExpression), this::compile);
        } catch (KeyResolveException e) {
            return new ValidationResult(List.of(e.getMessage()), List.of());
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        validateExpression(expression, method, mode != null ? mode : PropertyCheckMode.WARN,
                extraVariables != null ? extraVariables : Set.of(), errors, warnings);
        return new ValidationResult(errors, warnings);
    }

    /**
     * 使用默认属性检查级别（warn）校验 key 表达式。
     */
    public ValidationResult validate(Method method, String keyExpression) {
        return validate(method, keyExpression, PropertyCheckMode.WARN);
    }

    /**
     * 绑定参数名与位置别名到求值上下文。
     */
    private void bindVariables(SimpleEvaluationContext context, Method method, Object[] args) {
        String[] names = parameterNames(method);
        for (int i = 0; i < names.length && i < args.length; i++) {
            context.setVariable(names[i], args[i]);
        }
        for (int i = 0; i < args.length; i++) {
            context.setVariable("p" + i, args[i]);
            context.setVariable("a" + i, args[i]);
        }
    }

    /**
     * 遍历表达式 AST，收集校验问题。
     */
    private void validateExpression(Expression expression, Method method, PropertyCheckMode mode,
                                    Set<String> extraVariables, List<String> errors, List<String> warnings) {
        if (expression instanceof SpelExpression spelExpression) {
            validateNode(spelExpression.getAST(), method, mode, extraVariables, errors, warnings);
        } else if (expression instanceof CompositeStringExpression composite) {
            for (Expression part : composite.getExpressions()) {
                validateExpression(part, method, mode, extraVariables, errors, warnings);
            }
        }
    }

    /**
     * 递归遍历 AST 节点。
     *
     * <p>SpEL 的 {@code #order.id} 会被解析为 {@link CompoundExpression}：
     * 变量、属性/方法、索引器是兄弟节点而不是父子节点。因此遍历时要把前一个节点的
     * 推断类型作为后一个节点的目标类型，否则属性检查拿不到 {@code #order} 的类型。</p>
     */
    private void validateNode(SpelNode node, Method method, PropertyCheckMode mode,
                              Set<String> extraVariables, List<String> errors, List<String> warnings) {
        validateNode(node, method, mode, extraVariables, errors, warnings, null);
    }

    private void validateNode(SpelNode node, Method method, PropertyCheckMode mode,
                              Set<String> extraVariables, List<String> errors, List<String> warnings,
                              ResolvableType targetType) {
        if (node == null) {
            return;
        }
        if (node instanceof CompoundExpression) {
            ResolvableType current = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                SpelNode child = node.getChild(i);
                validateNode(child, method, mode, extraVariables, errors, warnings, current);
                ResolvableType childType = inferType(child, method, current);
                if (childType != null) {
                    current = childType;
                }
            }
            return;
        }
        if (node instanceof VariableReference variable) {
            validateVariable(variable.toStringAST().substring(1), method, extraVariables, errors);
        } else if (node instanceof TypeReference) {
            errors.add("不支持类型引用 T(...)：SimpleEvaluationContext 出于安全限制不开放类型/静态方法");
        } else if (node instanceof ConstructorReference) {
            errors.add("不支持 new ...：SimpleEvaluationContext 出于安全限制不开放构造器");
        } else if (node instanceof PropertyOrFieldReference property && mode != PropertyCheckMode.OFF) {
            if (targetType != null && lookupProperty(targetType, property.getName()) == null) {
                String message = "属性 '" + property.getName() + "' 在类型 " + typeName(targetType)
                        + " 上不存在；可用属性: " + availableProperties(targetType);
                addIssue(message, mode, errors, warnings);
            }
        } else if (node instanceof MethodReference methodReference && mode != PropertyCheckMode.OFF) {
            int argumentCount = Math.max(methodReference.getChildCount() - 1, 0);
            if (targetType != null
                    && lookupMethod(targetType, methodReference.getName(), argumentCount) == null) {
                String message = "方法 '" + methodReference.getName() + "(" + argumentCount + " 参数)' 在类型 "
                        + typeName(targetType) + " 上不存在";
                addIssue(message, mode, errors, warnings);
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            validateNode(node.getChild(i), method, mode, extraVariables, errors, warnings, null);
        }
    }

    /**
     * 校验变量名：允许参数名、#pN/#aN、#root、#this；位置变量越界单独报错。
     */
    private void validateVariable(String name, Method method, Set<String> extraVariables,
                                  List<String> errors) {
        if ("root".equals(name) || "this".equals(name)) {
            return;
        }
        if (extraVariables != null && extraVariables.contains(name)) {
            return;
        }
        Integer index = positionalIndex(name);
        if (index != null) {
            if (index < 0 || index >= method.getParameterCount()) {
                errors.add("位置变量 #" + name + " 越界：方法只有 " + method.getParameterCount() + " 个参数");
            }
            return;
        }
        for (String parameterName : parameterNames(method)) {
            if (parameterName.equals(name)) {
                return;
            }
        }
        errors.add("变量 #" + name + " 不存在；可用变量: " + availableVariables(method, extraVariables));
    }

    /**
     * 推断节点静态类型；无法推断时返回 null（跳过属性检查，避免动态类型误报）。
     *
     * @param targetType 当前 CompoundExpression 中前一个节点推断出的目标类型；
     *                   属性/方法/索引器节点据此确定宿主类型
     */
    private ResolvableType inferType(SpelNode node, Method method, ResolvableType targetType) {
        if (node == null) {
            return null;
        }
        if (node instanceof CompoundExpression) {
            ResolvableType current = null;
            for (int i = 0; i < node.getChildCount(); i++) {
                ResolvableType childType = inferType(node.getChild(i), method, current);
                if (childType != null) {
                    current = childType;
                }
            }
            return current;
        }
        if (node instanceof VariableReference variable) {
            String name = variable.toStringAST().substring(1);
            if ("root".equals(name)) {
                return ResolvableType.forClass(Object[].class);
            }
            if ("this".equals(name)) {
                return ResolvableType.forClass(Object.class);
            }
            Integer index = positionalIndex(name);
            if (index != null) {
                return index >= 0 && index < method.getParameterCount()
                        ? ResolvableType.forMethodParameter(method, index) : null;
            }
            String[] names = parameterNames(method);
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(name)) {
                    return ResolvableType.forMethodParameter(method, i);
                }
            }
            return null;
        }
        if (node instanceof PropertyOrFieldReference property) {
            ResolvableType owner = targetType;
            if (owner == null && property.getChildCount() > 0) {
                owner = inferType(property.getChild(0), method, null);
            }
            if (owner == null) {
                return null;
            }
            PropertyLookup lookup = lookupProperty(owner, property.getName());
            return lookup != null ? lookup.type() : null;
        }
        if (node instanceof Indexer indexer) {
            ResolvableType owner = targetType;
            if (owner == null && indexer.getChildCount() > 0) {
                owner = inferType(indexer.getChild(0), method, null);
            }
            if (owner == null) {
                return null;
            }
            if (owner.isArray()) {
                return owner.getComponentType();
            }
            Class<?> ownerClass = owner.resolve();
            if (ownerClass != null && Map.class.isAssignableFrom(ownerClass)) {
                return owner.as(Map.class).getGeneric(1);
            }
            if (ownerClass != null && Iterable.class.isAssignableFrom(ownerClass)) {
                return owner.as(Iterable.class).getGeneric(0);
            }
            return ResolvableType.forClass(Object.class);
        }
        if (node instanceof MethodReference methodReference) {
            ResolvableType owner = targetType;
            if (owner == null && methodReference.getChildCount() > 0) {
                owner = inferType(methodReference.getChild(0), method, null);
            }
            if (owner == null) {
                return null;
            }
            Method resolved = lookupMethod(owner, methodReference.getName(),
                    Math.max(methodReference.getChildCount() - 1, 0));
            return resolved != null ? ResolvableType.forMethodReturnType(resolved) : null;
        }
        return null;
    }

    /**
     * 尽力查找属性对应的字段/getter/record accessor。
     *
     * <p>Map、Collection、Optional、数组、Object 等动态/容器类型直接跳过，
     * 避免把 {@code #map['key']} 之类的正常写法误判为属性错误。</p>
     */
    private PropertyLookup lookupProperty(ResolvableType owner, String name) {
        Class<?> ownerClass = owner.resolve();
        if (ownerClass == null || ownerClass == Object.class || ownerClass.isPrimitive()
                || ownerClass.isArray() || Map.class.isAssignableFrom(ownerClass)
                || Collection.class.isAssignableFrom(ownerClass)
                || Optional.class.isAssignableFrom(ownerClass)) {
            return null;
        }
        // 按 SpEL 的实际访问顺序：public getter → record accessor → public field。
        // 不能接受 private/protected 字段：SimpleEvaluationContext 的
        // ReflectivePropertyAccessor 只通过 public 字段或 getter 读取属性。
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = ownerClass.getMethod(prefix + suffix);
                if (getter.getParameterCount() == 0) {
                    return new PropertyLookup(
                            ResolvableType.forMethodReturnType(getter, ownerClass), getter.getName());
                }
            } catch (NoSuchMethodException ignored) {
                // 尝试下一个前缀
            }
        }
        if (ownerClass.isRecord()) {
            try {
                Method accessor = ownerClass.getMethod(name);
                if (accessor.getParameterCount() == 0 && accessor.getReturnType() != void.class) {
                    return new PropertyLookup(
                            ResolvableType.forMethodReturnType(accessor, ownerClass), accessor.getName());
                }
            } catch (NoSuchMethodException ignored) {
                // record 没有该 accessor
            }
        }
        try {
            Field field = ownerClass.getField(name);
            if (!Modifier.isStatic(field.getModifiers())) {
                return new PropertyLookup(ResolvableType.forField(field, owner), field.getName());
            }
        } catch (NoSuchFieldException ignored) {
            // 没有 public 字段
        }
        return null;
    }

    /**
     * 查找无参/指定参数数量的实例方法；同名同参数数量但返回类型不一致时视为不确定，跳过检查。
     */
    private Method lookupMethod(ResolvableType owner, String name, int argumentCount) {
        Class<?> ownerClass = owner.resolve();
        if (ownerClass == null || ownerClass == Object.class || Map.class.isAssignableFrom(ownerClass)) {
            return null;
        }
        Method candidate = null;
        for (Method method : ownerClass.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != argumentCount
                    || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (candidate == null) {
                candidate = method;
            } else if (!candidate.getReturnType().equals(method.getReturnType())) {
                return null;
            }
        }
        return candidate;
    }

    /**
     * 生成可用属性提示，便于用户直接修正拼写。
     */
    private String availableProperties(ResolvableType owner) {
        Class<?> ownerClass = owner.resolve();
        if (ownerClass == null) {
            return "<unknown>";
        }
        Set<String> names = new TreeSet<>();
        for (Field field : ownerClass.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(field.getName());
            }
        }
        for (Method method : ownerClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (name.startsWith("get") && name.length() > 3) {
                names.add(decapitalize(name.substring(3)));
            } else if (name.startsWith("is") && name.length() > 2) {
                names.add(decapitalize(name.substring(2)));
            } else if (ownerClass.isRecord()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "<unknown>" : String.join(", ", names);
    }

    private String availableVariables(Method method, Set<String> extraVariables) {
        List<String> variables = new ArrayList<>();
        String[] names = parameterNames(method);
        if (names.length == 0) {
            variables.add("<参数名不可用，请使用 #p0/#a0 或编译时加 -parameters>");
        } else {
            variables.addAll(Arrays.asList(names));
        }
        for (int i = 0; i < method.getParameterCount(); i++) {
            variables.add("p" + i);
            variables.add("a" + i);
        }
        variables.add("root");
        variables.add("this");
        if (extraVariables != null) {
            variables.addAll(extraVariables);
        }
        return String.join(", ", variables);
    }

    private String[] parameterNames(Method method) {
        return parameterNamesCache.computeIfAbsent(method, target -> {
            String[] names = parameterNameDiscoverer.getParameterNames(target);
            return names != null ? names : EMPTY_NAMES;
        });
    }

    /**
     * 解析 #pN / #aN 位置别名；不是位置别名时返回 null。
     */
    private static Integer positionalIndex(String name) {
        if (name.length() < 2 || (name.charAt(0) != 'p' && name.charAt(0) != 'a')) {
            return null;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) {
                return null;
            }
        }
        try {
            return Integer.parseInt(name.substring(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String decapitalize(String value) {
        return value.isEmpty() ? value : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String typeName(ResolvableType type) {
        Class<?> resolved = type.resolve();
        return resolved != null ? resolved.getName() : type.toString();
    }

    private static void addIssue(String message, PropertyCheckMode mode,
                                 List<String> errors, List<String> warnings) {
        if (mode == PropertyCheckMode.STRICT) {
            errors.add(message);
        } else {
            warnings.add(message);
        }
    }

    /**
     * 编译表达式；模板表达式（含 #{...}）按模板解析，否则按纯表达式解析。
     * 编译失败直接抛出，不缓存失败结果——key 是必需语义，应尽早暴露配置错误。
     */
    private Expression compile(CacheKey cacheKey) {
        try {
            if (cacheKey.keyExpression().contains("#{")) {
                return parser.parseExpression(cacheKey.keyExpression(), new TemplateParserContext());
            }
            return parser.parseExpression(cacheKey.keyExpression());
        } catch (Exception e) {
            throw new KeyResolveException("key 表达式编译失败 - method: " + cacheKey.method().getName()
                    + ", key: '" + cacheKey.keyExpression() + "'", e);
        }
    }

    /**
     * 缓存条目：方法 + 表达式原文（同一方法可被不同注解表达式复用，二者共同作为缓存键）。
     */
    private record CacheKey(Method method, String keyExpression) {
    }

    /**
     * 属性查找结果：属性类型与访问器名称。
     */
    private record PropertyLookup(ResolvableType type, String accessor) {
    }

    /**
     * 属性/方法名检查级别。
     */
    public enum PropertyCheckMode {
        /** 只校验语法和变量名。 */
        OFF,
        /** 属性/方法名疑似写错时记录警告。 */
        WARN,
        /** 属性/方法名找不到时视为错误。 */
        STRICT
    }

    /**
     * 校验结果：错误会阻止启动（fail-fast 时），警告只记录日志。
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {

        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public static ValidationResult empty() {
            return new ValidationResult(List.of(), List.of());
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public boolean hasIssues() {
            return !errors.isEmpty() || !warnings.isEmpty();
        }
    }

    /**
     * key 解析失败异常：表示注解配置错误或表达式求值出错，属于编程错误，应快速失败暴露。
     */
    public static class KeyResolveException extends RuntimeException {
        public KeyResolveException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
