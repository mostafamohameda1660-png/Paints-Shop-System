package com.shop.dao;

import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.PriceHistory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PriceHistoryDAO {

    // إضافة سجل تغير سعر
    public void insert(PriceHistory priceHistory) {

        String sql = """
        INSERT INTO Price_History
        (
        product_id,
        price_type,
        old_price,
        new_price,
        date
        )
        VALUES(?,?,?,?,?)

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setInt(1, priceHistory.getProductId());
            stmt.setString(2, priceHistory.getPriceType());
            stmt.setDouble(3, priceHistory.getOldPrice());
            stmt.setDouble(4, priceHistory.getNewPrice());
            stmt.setString(5, priceHistory.getDate());

            stmt.executeUpdate();

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في إضافة سجل تغير السعر", e);
        }

    }

    // جلب تاريخ أسعار منتج
    public List<PriceHistory> findByProduct(int productId) {

        List<PriceHistory> histories = new ArrayList<>();

        String sql = """
        SELECT *
        FROM Price_History
        WHERE product_id = ?
        ORDER BY date DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setInt(1, productId);

            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                histories.add(mapPriceHistory(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب تاريخ أسعار المنتج", e);
        }

        return histories;

    }

    // جلب كل تاريخ الأسعار
    public List<PriceHistory> findAll() {

        List<PriceHistory> histories = new ArrayList<>();

        String sql = """
        SELECT *
        FROM Price_History
        ORDER BY date DESC

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ) {

            while (rs.next()) {
                histories.add(mapPriceHistory(rs));
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب تاريخ الأسعار", e);
        }

        return histories;

    }

    // جلب أحدث تغير سعر لمنتج
    public PriceHistory findLatestByProduct(int productId, String priceType) {

        String sql = """
        SELECT *
        FROM Price_History
        WHERE product_id = ?
        AND price_type = ?
        ORDER BY date DESC
        LIMIT 1

        """;

        try (
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {

            stmt.setInt(1, productId);
            stmt.setString(2, priceType);

            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return mapPriceHistory(rs);
            }

        } catch (SQLException e) {
            throw new DatabaseException("خطأ في جلب أحدث تغير سعر", e);
        }

        return null;

    }

    // تحويل ResultSet إلى PriceHistory
    private PriceHistory mapPriceHistory(ResultSet rs) throws SQLException {

        PriceHistory priceHistory = new PriceHistory();

        priceHistory.setId(
                rs.getInt("id")
        );

        priceHistory.setProductId(
                rs.getInt("product_id")
        );

        priceHistory.setPriceType(
                rs.getString("price_type")
        );

        priceHistory.setOldPrice(
                rs.getDouble("old_price")
        );

        priceHistory.setNewPrice(
                rs.getDouble("new_price")
        );

        priceHistory.setDate(
                rs.getString("date")
        );

        return priceHistory;

    }

}
