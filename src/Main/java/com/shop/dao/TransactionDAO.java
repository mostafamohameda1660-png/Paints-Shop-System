package com.shop.dao;

import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.Transaction;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class TransactionDAO {

    // إضافة معاملة
    public void insert(Transaction transaction) {

        String sql = """
        INSERT INTO Transactions
        (
        product_id,
        batch_id,
        type,
        quantity,
        price,
        profit,
        date_time,
        notes
        )
        VALUES(?,?,?,?,?,?,?,?)

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            if (transaction.getProductId() > 0) {
                stmt.setInt(1, transaction.getProductId());
            } else {
                stmt.setNull(1, Types.INTEGER);
            }

            if (transaction.getBatchId() > 0) {
                stmt.setInt(2, transaction.getBatchId());
            } else {
                stmt.setNull(2, Types.INTEGER);
            }

            stmt.setString(3, transaction.getType());
            stmt.setDouble(4, transaction.getQuantity());
            stmt.setDouble(5, transaction.getPrice());
            stmt.setDouble(6, transaction.getProfit());
            stmt.setString(7, transaction.getDateTime());
            stmt.setString(8, transaction.getNotes());

            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في إضافة المعاملة", e);
        }

    }

    // جلب كل معاملات منتج
    public List<Transaction> findByProduct(int productId) {

        List<Transaction> transactions = new ArrayList<>();

        String sql = """
        SELECT *
        FROM Transactions
        WHERE product_id = ?
        ORDER BY date_time DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setInt(1, productId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                transactions.add(mapTransaction(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب معاملات المنتج", e);
        }

        return transactions;

    }

    // جلب كل المعاملات
    public List<Transaction> findAll() {

        List<Transaction> transactions = new ArrayList<>();

        String sql = """
        SELECT *
        FROM Transactions
        ORDER BY date_time DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            while (rs.next()) {
                transactions.add(mapTransaction(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب المعاملات", e);
        }

        return transactions;

    }

    // جلب معاملة بالرقم
    public Transaction findById(int id) {

        String sql = """
        SELECT *
        FROM Transactions
        WHERE id = ?

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setInt(1, id);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapTransaction(rs);
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب المعاملة بالرقم", e);
        }

        return null;

    }

    // جلب معاملات المبيعات القابلة للمرتجع
    public List<Transaction> findReturnableSales() {

        List<Transaction> transactions = new ArrayList<>();

        String sql = """
        SELECT *
        FROM Transactions
        WHERE type = 'OUT'
        AND quantity > returned_quantity
        ORDER BY date_time DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            while (rs.next()) {
                transactions.add(mapTransaction(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب المبيعات القابلة للمرتجع", e);
        }

        return transactions;

    }

    // تحديث الكمية المرتجعة
    public void updateReturnedQuantity(int id, double returnedQuantity) {

        String sql = """
        UPDATE Transactions
        SET returned_quantity = returned_quantity + ?
        WHERE id = ?

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setDouble(1, returnedQuantity);
            stmt.setInt(2, id);

            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في تحديث الكمية المرتجعة", e);
        }

    }

    // تحويل ResultSet إلى Transaction
    private Transaction mapTransaction(ResultSet rs) throws SQLException {

        Transaction transaction = new Transaction();

        transaction.setId(
                rs.getInt("id")
        );

        transaction.setProductId(
                rs.getInt("product_id")
        );

        try {
            transaction.setBatchId(rs.getInt("batch_id"));
        } catch (Exception ignored) {}

        transaction.setType(
                rs.getString("type")
        );

        transaction.setQuantity(
                rs.getDouble("quantity")
        );

        transaction.setPrice(
                rs.getDouble("price")
        );

        transaction.setProfit(
                rs.getDouble("profit")
        );

        transaction.setDateTime(
                rs.getString("date_time")
        );

        transaction.setNotes(
                rs.getString("notes")
        );

        return transaction;

    }

}
