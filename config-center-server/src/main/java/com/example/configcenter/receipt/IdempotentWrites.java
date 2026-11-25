package com.example.configcenter.receipt;

import com.example.configcenter.exception.BizException;
import com.example.configcenter.exception.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;
import java.util.regex.Pattern;

@Component
public class IdempotentWrites {
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private final TransactionTemplate transaction;
    private final WriteReceiptStore receipts;
    private final WriteReceiptCodec codec;

    public IdempotentWrites(PlatformTransactionManager manager, WriteReceiptStore receipts, WriteReceiptCodec codec) {
        this.transaction = new TransactionTemplate(manager);
        this.receipts = receipts;
        this.codec = codec;
    }

    public <T> Result<T> execute(WriteIntent intent, byte[] key, Class<T> type, Supplier<T> write) {
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

    public record Result<T>(T data, boolean replayed) {}
}
