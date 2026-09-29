package io.github.biglv666.guard.itest.discovery;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试可发现性守卫（纯源码扫描，不依赖 Spring/Redis）：
 * 防止"测试存在但从未执行"的静默回归。
 *
 * <p>背景：JUnit 5 不会发现 {@code static} 嵌套类中的 {@code @Test} 方法——
 * 历史上 {@code IdempotentDegradeTest} 曾用静态嵌套 {@code @SpringBootTest} 类，
 * 整个测试静默跑了 0 个用例而构建依然全绿。本测试直接扫描 {@code src/test/java}
 * 源码文本，把这类结构性错误变成显式的红灯。
 *
 * <p>检查规则：
 * <ol>
 *   <li>任何 {@code static class} 声明的类体内不允许出现 {@code @Test}
 *       （嵌套测试类必须用 {@code @Nested} 非静态内部类）；</li>
 *   <li>测试目录下至少存在一个可发现的测试方法（防误删整个测试套件）。</li>
 * </ol>
 *
 * @author Guard Team
 * @since 0.2.1
 */
class TestDiscoveryGuard {

    private static final Path TEST_SRC = Path.of("src/test/java");
    private static final Pattern STATIC_CLASS = Pattern.compile("\\bstatic\\s+class\\s+\\w+");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@Test\\b");
    private static final Pattern NESTED_ANNOTATION = Pattern.compile("@Nested\\b");

    @Test
    void noTestMethodsInsideStaticNestedClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : listJavaFiles()) {
            String source = Files.readString(file);
            // 粗粒度结构扫描：定位每个 static class 声明，检查到文件末尾（或下一个同级
            // class 声明）之间是否出现 @Test。不要求完整解析 Java，足以捕获本类缺陷。
            Matcher classMatcher = STATIC_CLASS.matcher(source);
            while (classMatcher.find()) {
                int bodyStart = source.indexOf('{', classMatcher.end());
                if (bodyStart < 0) {
                    continue;
                }
                int bodyEnd = matchingBrace(source, bodyStart);
                String body = source.substring(bodyStart, bodyEnd);
                // static class 体内的 @Test 若属于其内部的 @Nested 非静态类则是合法的；
                // 剔除嵌套类体后再判断
                String bodyWithoutNested = stripNestedBodies(body);
                if (TEST_ANNOTATION.matcher(bodyWithoutNested).find()) {
                    violations.add(file + "（static class 内直接声明 @Test，JUnit 5 不会发现）");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "发现静态嵌套类中直接声明 @Test 的测试（JUnit 5 不会执行）：\n" + String.join("\n", violations));
    }

    @Test
    void testSuiteIsNotEmpty() throws IOException {
        long testMethods = 0;
        for (Path file : listJavaFiles()) {
            String source = Files.readString(file);
            Matcher m = TEST_ANNOTATION.matcher(source);
            while (m.find()) {
                testMethods++;
            }
        }
        assertTrue(testMethods >= 20,
                "测试套件规模异常缩小（@Test 方法数=" + testMethods + "），可能误删了测试文件");
    }

    /**
     * 剔除 {@code @Nested} 标注的非静态内部类的类体（其中的 @Test 是合法的）。
     */
    private static String stripNestedBodies(String classBody) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < classBody.length()) {
            Matcher nested = NESTED_ANNOTATION.matcher(classBody);
            if (!nested.find(i)) {
                result.append(classBody, i, classBody.length());
                break;
            }
            int classKeyword = classBody.indexOf("class", nested.end());
            if (classKeyword < 0) {
                result.append(classBody, i, classBody.length());
                break;
            }
            int brace = classBody.indexOf('{', classKeyword);
            if (brace < 0) {
                result.append(classBody, i, classBody.length());
                break;
            }
            result.append(classBody, i, nested.start());
            i = matchingBrace(classBody, brace);
        }
        return result.toString();
    }

    /**
     * 从 {@code openBrace}（必须是 '{'）开始找到匹配的 '}' 的后一个位置。
     */
    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return source.length();
    }

    private static List<Path> listJavaFiles() throws IOException {
        assertTrue(Files.isDirectory(TEST_SRC), "测试源码目录不存在: " + TEST_SRC.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(TEST_SRC)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
