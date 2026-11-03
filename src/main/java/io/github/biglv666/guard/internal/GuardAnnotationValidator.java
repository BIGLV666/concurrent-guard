package io.github.biglv666.guard.internal;

import io.github.biglv666.guard.GuardProperties;
import io.github.biglv666.guard.GuardValidationException;
import io.github.biglv666.guard.idempotent.Idempotent;
import io.github.biglv666.guard.idempotent.IdempotentMode;
import io.github.biglv666.guard.lock.DistributedLock;
import io.github.biglv666.guard.lock.LockAcquireFallbackHandler;
import io.github.biglv666.guard.lock.LockAcquirePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 启动期 Guard 注解校验器。
 *
 * <p>在 Spring 容器单例初始化完成后扫描所有 Bean 的 {@code @Idempotent} /
 * {@code @DistributedLock} 方法，集中校验：
 * <ul>
 *   <li>SpEL 语法、变量名、位置变量越界、类型引用/构造器；</li>
 *   <li>属性/方法名拼写（按 {@code guard.validation.property-check} 决定告警或失败）；</li>
 *   <li>注解属性组合（ttl、waitMillis、waitTime、leaseTime、CUSTOM handler 等）。</li>
 * </ul>
 *
 * <p>默认 fail-fast，一次性抛出包含全部问题的 {@link GuardValidationException}；
 * 可通过 {@code guard.validation.fail-fast=false} 改为只记录日志。</p>
 *
 * @author Guard Team
 * @since 0.2.2
 */
public class GuardAnnotationValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(GuardAnnotationValidator.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final SpelKeyResolver keyResolver;
    private final GuardProperties.Validation properties;

    public GuardAnnotationValidator(ConfigurableListableBeanFactory beanFactory,
                                    SpelKeyResolver keyResolver,
                                    GuardProperties.Validation properties) {
        this.beanFactory = beanFactory;
        this.keyResolver = keyResolver;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (properties == null || !properties.isEnabled()) {
            return;
        }
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SpelKeyResolver.PropertyCheckMode mode = propertyCheckMode(warnings);

        Set<String> validated = new HashSet<>();
        for (String beanName : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            Class<?> type;
            try {
                type = beanFactory.getType(beanName);
            } catch (RuntimeException e) {
                continue;
            }
            if (type == null || isInfrastructure(type)) {
                continue;
            }
            scanClass(ClassUtils.getUserClass(type), mode, errors, warnings, validated);

            // JDK 动态代理的 bean type 是接口，注解可能只写在实现类上；
            // 对已创建的非懒加载单例再按目标类扫描一次（不主动实例化 @Lazy Bean）。
            if (!isLazy(beanName)) {
                try {
                    Object bean = beanFactory.getBean(beanName);
                    Class<?> targetClass = AopUtils.getTargetClass(bean);
                    if (targetClass != null) {
                        scanClass(ClassUtils.getUserClass(targetClass), mode, errors, warnings, validated);
                    }
                } catch (RuntimeException ignored) {
                    // 某些 FactoryBean/作用域代理无法安全取实例，跳过目标类扫描即可
                }
            }
        }

        if (!warnings.isEmpty()) {
            log.warn("Guard 注解校验发现 {} 条警告：\n{}", warnings.size(), format(warnings));
        }
        if (!errors.isEmpty()) {
            String message = "Guard 注解校验失败，共 " + errors.size() + " 处：\n" + format(errors);
            if (properties.isFailFast()) {
                throw new GuardValidationException(message, errors);
            }
            log.error(message);
        }
    }

    private void scanClass(Class<?> userClass, SpelKeyResolver.PropertyCheckMode mode,
                           List<String> errors, List<String> warnings, Set<String> validated) {
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(userClass)) {
            if (method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!validated.add(signature(method))) {
                continue;
            }
            validateMethod(method, mode, errors, warnings);
        }
    }

    private boolean isLazy(String beanName) {
        return beanFactory.containsBeanDefinition(beanName)
                && beanFactory.getBeanDefinition(beanName).isLazyInit();
    }

    private void validateMethod(Method method, SpelKeyResolver.PropertyCheckMode mode,
                                List<String> errors, List<String> warnings) {
        Idempotent idempotent = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
        if (idempotent != null) {
            validateIdempotent(method, idempotent, mode, errors, warnings);
        }
        DistributedLock lock = AnnotatedElementUtils.findMergedAnnotation(method, DistributedLock.class);
        if (lock != null) {
            validateLock(method, lock, mode, errors, warnings);
        }
    }

    private void validateIdempotent(Method method, Idempotent idempotent,
                                    SpelKeyResolver.PropertyCheckMode mode,
                                    List<String> errors, List<String> warnings) {
        if (idempotent.ttl() <= 0
                || idempotent.timeUnit().toMillis(idempotent.ttl()) <= 0) {
            addError(errors, method, "@Idempotent ttl 必须大于 0，当前: "
                    + idempotent.ttl() + " " + idempotent.timeUnit());
        }
        if (idempotent.waitMillis() < 0) {
            addError(errors, method, "@Idempotent waitMillis 不能为负，当前: " + idempotent.waitMillis());
        }
        if (idempotent.waitMillis() > 0 && idempotent.mode() != IdempotentMode.REPLAY) {
            addError(errors, method, "@Idempotent waitMillis 仅支持 REPLAY 模式，当前 mode: "
                    + idempotent.mode());
        }
        if (idempotent.message() == null || idempotent.message().isBlank()) {
            addWarning(warnings, method, "@Idempotent message 为空，拒绝时异常消息将缺少业务提示");
        }
        validateKey(method, "@Idempotent", idempotent.key(), mode, errors, warnings);
        validateKey(method, "@Idempotent(condition)", idempotent.condition(), mode,
                Set.of(), errors, warnings);
        validateKey(method, "@Idempotent(unless)", idempotent.unless(), mode,
                Set.of("result"), errors, warnings);
    }

    private void validateLock(Method method, DistributedLock lock,
                              SpelKeyResolver.PropertyCheckMode mode,
                              List<String> errors, List<String> warnings) {
        if (lock.waitTime() < 0) {
            addError(errors, method, "@DistributedLock waitTime 不能为负，当前: " + lock.waitTime());
        }
        if (lock.leaseTime() < -1) {
            addError(errors, method, "@DistributedLock leaseTime 必须 >= -1，当前: " + lock.leaseTime());
        }
        if (lock.acquirePolicy() == LockAcquirePolicy.CUSTOM
                && lock.handler() == LockAcquireFallbackHandler.class) {
            addError(errors, method, "@DistributedLock acquirePolicy=CUSTOM 必须通过 handler 指定回退处理器");
        }
        if (lock.acquirePolicy() == LockAcquirePolicy.SKIP
                && method.getReturnType().isPrimitive() && method.getReturnType() != void.class) {
            addError(errors, method, "@DistributedLock acquirePolicy=SKIP 不能用于基本类型返回值方法 "
                    + method.getReturnType().getName() + "（超时返回 null 会导致拆箱异常）");
        }
        if (java.util.concurrent.CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            addError(errors, method, "@DistributedLock 不支持返回 CompletionStage/CompletableFuture 的方法："
                    + "锁必须由获取线程释放，异步完成线程无法安全解锁；请在异步任务内部使用 LockTemplate "
                    + "或改用幂等/其他并发控制方案");
        }
        validateKey(method, "@DistributedLock", lock.key(), mode, errors, warnings);
    }

    private void validateKey(Method method, String annotation, String keyExpression,
                             SpelKeyResolver.PropertyCheckMode mode,
                             List<String> errors, List<String> warnings) {
        validateKey(method, annotation, keyExpression, mode, Set.of(), errors, warnings);
    }

    private void validateKey(Method method, String annotation, String keyExpression,
                             SpelKeyResolver.PropertyCheckMode mode, Set<String> extraVariables,
                             List<String> errors, List<String> warnings) {
        if (keyExpression == null || keyExpression.isBlank()) {
            return;
        }
        SpelKeyResolver.ValidationResult result =
                keyResolver.validate(method, keyExpression, mode, extraVariables);
        for (String error : result.errors()) {
            addError(errors, method, annotation + " 表达式: " + error);
        }
        for (String warning : result.warnings()) {
            addWarning(warnings, method, annotation + " 表达式: " + warning);
        }
    }

    private SpelKeyResolver.PropertyCheckMode propertyCheckMode(List<String> warnings) {
        String configured = properties.getPropertyCheck();
        if (configured == null || configured.isBlank()) {
            return SpelKeyResolver.PropertyCheckMode.WARN;
        }
        switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "off":
                return SpelKeyResolver.PropertyCheckMode.OFF;
            case "strict":
                return SpelKeyResolver.PropertyCheckMode.STRICT;
            case "warn":
                return SpelKeyResolver.PropertyCheckMode.WARN;
            default:
                warnings.add("guard.validation.property-check 配置值非法: '" + configured
                        + "'，可选 off/warn/strict，已按 warn 处理");
                return SpelKeyResolver.PropertyCheckMode.WARN;
        }
    }

    private static boolean isInfrastructure(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")
                || name.startsWith("sun.") || name.startsWith("com.sun.")
                || name.startsWith("org.springframework.") || name.startsWith("org.apache.");
    }

    private static String signature(Method method) {
        StringBuilder builder = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getName());
        }
        return builder.append(')').toString();
    }

    private static void addError(List<String> errors, Method method, String message) {
        errors.add(signature(method) + " -> " + message);
    }

    private static void addWarning(List<String> warnings, Method method, String message) {
        warnings.add(signature(method) + " -> " + message);
    }

    private static String format(List<String> messages) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            builder.append(i + 1).append(". ").append(messages.get(i));
            if (i < messages.size() - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }
}
