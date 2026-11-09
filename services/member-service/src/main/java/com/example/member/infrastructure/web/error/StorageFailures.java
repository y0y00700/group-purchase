package com.example.member.infrastructure.web.error;
import java.util.Set;
public final class StorageFailures {
    private StorageFailures() { }
    public static boolean unavailable(Throwable error) {
        for(Throwable t=error; t!=null; t=t.getCause()) {
            if(t instanceof org.springframework.transaction.TransactionSystemException tx
                && tx.getApplicationException()!=null && tx.getApplicationException()!=t
                && unavailable(tx.getApplicationException())) return true;
            if(t instanceof org.springframework.jdbc.CannotGetJdbcConnectionException
                || t instanceof org.springframework.dao.DataAccessResourceFailureException
                || t instanceof org.springframework.dao.QueryTimeoutException
                || t instanceof org.springframework.transaction.CannotCreateTransactionException
                || t instanceof java.sql.SQLTransientConnectionException) return true;
            if(t instanceof java.sql.SQLException sql && sql.getSQLState()!=null
                && (sql.getSQLState().startsWith("08") || Set.of("57P01","57P02","57P03").contains(sql.getSQLState()))) return true;
        }
        return false;
    }
}
