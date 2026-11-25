package com.example.configcenter.receipt;

import com.example.configcenter.dto.ApiResponse;
import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class IdempotentWrites {
    public static final String KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotency-Replayed";
    public static final String KEY_PATTERN = "[A-Za-z0-9._-]{1,128}";
    public static final String KEY_DESCRIPTION = "Optional request identifier within an exact app/env. Reuse it with the same body to retry.";
    private static final Pattern VALID_KEY = Pattern.compile(KEY_PATTERN);
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final TransactionTemplate transaction;
    private final WriteReceiptStore receipts;
    private final WriteReceiptCodec codec;

    public IdempotentWrites(PlatformTransactionManager manager, WriteReceiptStore receipts, WriteReceiptCodec codec) {
        this.transaction = new TransactionTemplate(manager);
        this.receipts = receipts;
        this.codec = codec;
    }

    // Controllers call this after body validation and current namespace authorization.
    public <T> ResponseEntity<ApiResponse<T>> respond(HttpServletRequest request, Supplier<WriteIntent> intent,
                                                     Class<T> type, Supplier<T> write) {
        var headers = request.getHeaders(KEY);
        if (!headers.hasMoreElements()) {
            return ResponseEntity.ok(ApiResponse.ok(write.get()));
        }
        String key = headers.nextElement();
        if (headers.hasMoreElements() || !VALID_KEY.matcher(key).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "Idempotency-Key 格式无效");
        }
        Result<T> result = execute(intent.get(), key.getBytes(StandardCharsets.US_ASCII), type, write);
        return ResponseEntity.ok().header(REPLAYED, Boolean.toString(result.replayed()))
                .body(ApiResponse.ok(result.data()));
    }

    private <T> Result<T> execute(WriteIntent intent, byte[] key, Class<T> type, Supplier<T> write) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The idempotent entry point must own the transaction");
        }
        try {
            return transaction.execute(status -> receipts.find(intent, key)
                    .map(receipt -> replay(receipt, intent, type))
                    .orElseGet(() -> {
                        receipts.claim(intent, key);
                        T data = write.get();
                        receipts.complete(intent, key, codec.encode(data));
                        return new Result<>(data, false);
                    }));
        } catch (WriteReceiptStore.ClaimConflict contention) {
            // The failed transaction and its repeatable-read snapshot are gone. Only read here.
            return transaction.execute(status -> receipts.find(intent, key)
                    .map(receipt -> replay(receipt, intent, type))
                    .orElseThrow(() -> new BizException(ErrorCode.CONFLICT,
                            "请求编号正在竞争，请使用相同编号和原请求再次尝试")));
        }
    }

    private <T> Result<T> replay(WriteReceiptStore.Stored receipt, WriteIntent intent, Class<T> type) {
        if (receipt.intentVersion() != WriteIntent.VERSION || receipt.snapshotVersion() != WriteReceiptCodec.VERSION
                || receipt.snapshot() == null || receipt.digest() == null || !DIGEST.matcher(receipt.digest()).matches()) {
            throw new IllegalStateException("Invalid or unsupported write receipt");
        }
        WriteIntent.Operation operation;
        try {
            operation = WriteIntent.Operation.valueOf(receipt.operation());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalStateException("Invalid write receipt operation");
        }
        if (intent.operation() != operation || !intent.digest().equals(receipt.digest())) {
            throw new BizException(ErrorCode.CONFLICT, "请求编号已用于不同的写入");
        }
        return new Result<>(codec.decode(receipt.snapshot(), intent, type), true);
    }

    private record Result<T>(T data, boolean replayed) {}
}
