package com.shop.dao;

import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.CashLedger;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CashLedgerDAO {

    // إضافة قيد خزينة
    public void insert(CashLedger cashLedger) {

        String sql = """
        INSERT INTO CashLedger
        (
        type,
        amount,
        date_time,
        related_transaction_id,
        notes,
        person_name
        )
        VALUES(?,?,?,?,?,?)

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setString(1, cashLedger.getType());
            stmt.setDouble(2, cashLedger.getAmount());
            stmt.setString(3, cashLedger.getDateTime());

            if (cashLedger.getRelatedTransactionId() > 0) {
                stmt.setInt(4, cashLedger.getRelatedTransactionId());
            } else {
                stmt.setNull(4, Types.INTEGER);
            }

            stmt.setString(5, cashLedger.getNotes());
            stmt.setString(6, cashLedger.getPersonName());

            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في إضافة قيد الخزينة", e);
        }

    }

    // جلب كل قيود الخزينة
    public List<CashLedger> findAll() {

        List<CashLedger> ledgers = new ArrayList<>();

        String sql = """
        SELECT *
        FROM CashLedger
        ORDER BY date_time DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            while (rs.next()) {
                ledgers.add(mapCashLedger(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب قيود الخزينة", e);
        }

        return ledgers;

    }

    // جلب قيود الخزينة حسب التاريخ
    public List<CashLedger> findByDate(String date) {

        List<CashLedger> ledgers = new ArrayList<>();

        String sql = """
        SELECT *
        FROM CashLedger
        WHERE date(date_time) = ?
        ORDER BY date_time DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setString(1, date);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                ledgers.add(mapCashLedger(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب قيود الخزينة بالتاريخ", e);
        }

        return ledgers;

    }

    // جلب إجمالي الإيداعات
    public double getTotalDeposits() {

        String sql = """
        SELECT COALESCE(SUM(amount), 0)
        FROM CashLedger
        WHERE type = 'DEPOSIT'

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            if (rs.next()) {
                return rs.getDouble(1);
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب إجمالي الإيداعات", e);
        }

        return 0;

    }

    // جلب إجمالي المسحوبات
    public double getTotalWithdrawals() {

        String sql = """
        SELECT COALESCE(SUM(amount), 0)
        FROM CashLedger
        WHERE type = 'WITHDRAWAL'

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            if (rs.next()) {
                return rs.getDouble(1);
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب إجمالي المسحوبات", e);
        }

        return 0;

    }

    // تحويل ResultSet إلى CashLedger
    private CashLedger mapCashLedger(ResultSet rs) throws SQLException {

        CashLedger cashLedger = new CashLedger();

        cashLedger.setId(
                rs.getInt("id")
        );

        cashLedger.setType(
                rs.getString("type")
        );

        cashLedger.setAmount(
                rs.getDouble("amount")
        );

        cashLedger.setDateTime(
                rs.getString("date_time")
        );

        try {
            cashLedger.setRelatedTransactionId(rs.getInt("related_transaction_id"));
        } catch (Exception ignored) {}

        cashLedger.setNotes(
                rs.getString("notes")
        );

        try {
            cashLedger.setPersonName(rs.getString("person_name"));
        } catch (Exception ignored) {}

        return cashLedger;

    }

}
