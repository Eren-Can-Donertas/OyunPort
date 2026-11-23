package tr.edu.etu.oyunport.db;

import java.sql.SQLException;

/** Tek bir veritabanı transaction'ı içinde çalışan iş birimi. */
@FunctionalInterface
public interface TxWork<T> {
    T run(Tx tx) throws SQLException;
}
