package io.github.biglv666.guard;

import java.util.List;

/**
 * Guard 启动期注解/SpEL 校验失败异常。
 *
 * <p>默认在应用启动阶段抛出，一次性列出所有发现的问题，便于集中修复。
 * 可通过 {@code guard.validation.fail-fast=false} 改为只记录 error 日志。</p>
 *
 * @author Guard Team
 * @since 0.2.2
 */
public class GuardValidationException extends IllegalStateException {

    private final List<String> errors;

    public GuardValidationException(String message, List<String> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    /**
     * 本次校验发现的全部错误描述。
     */
    public List<String> getErrors() {
        return errors;
    }
}
