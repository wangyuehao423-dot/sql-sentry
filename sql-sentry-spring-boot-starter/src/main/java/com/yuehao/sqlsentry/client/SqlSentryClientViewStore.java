package com.yuehao.sqlsentry.client;

import com.yuehao.sqlsentry.model.SqlClientDiagnosis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SqlSentryClientViewStore {

    private final Map<String, SqlClientDiagnosis> diagnoses = new ConcurrentHashMap<String, SqlClientDiagnosis>();

    public void save(SqlClientDiagnosis diagnosis) {
        if (diagnosis == null || !hasText(diagnosis.getFingerprint())) {
            return;
        }
        diagnoses.put(diagnosis.getFingerprint(), diagnosis);
    }

    public SqlClientDiagnosis findByFingerprint(String fingerprint) {
        if (!hasText(fingerprint)) {
            return null;
        }
        return diagnoses.get(fingerprint.trim());
    }

    public SqlClientDiagnosis awaitByFingerprint(String fingerprint, long timeoutMs) {
        if (!hasText(fingerprint)) {
            return null;
        }

        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0L);
        SqlClientDiagnosis diagnosis = findByFingerprint(fingerprint);
        while (diagnosis == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            diagnosis = findByFingerprint(fingerprint);
        }
        return diagnosis;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
