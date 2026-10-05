package com.shop.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.shop.database.DatabaseManager;
import com.shop.exception.InsufficientStockException;
import com.shop.exception.ValidationException;
import com.shop.model.Product;
import com.shop.model.enums.UnitType;
import com.shop.utils.ValidationUtil;

public final class InventoryService {
    public record ProductRow(int id, String code, String name, String company, String category, String unit, double quantity, double sellingPrice, double wholesalePrice, double packageWeight, String notes, String measurementUnit, String unitName, double unitCount, String createdAt, String supplierCode, String supplierName, String supplierPhone) {}
    public record SaleRow(long transactionId, String code, String product, double quantity, double price, double returnedQuantity, String dateTime, String notes, String customerCode, String customerName) {}
    public record Dashboard(int products, double stockValue, double todaySales, double todayPurchases, double todayProfit, int lowStock) {}
    public record DailyCard(int id, String date, double sales, double profit, double wholesale, double balance, double deposit, double withdrawal, int transactionCount, boolean closed) {}
    public record DailyTransaction(long id, String type, String productName, double quantity, double price, double profit, String time, String notes, String customerCode, String customerName, String supplierCode, String supplierName, String measurementUnit, String unitType, double packageWeight) {}
    public record TreasurySummary(double todayBalance, double todaySales, double todayProfit, double todayWholesale, double monthBalance) {}
    public record DailyCardRow(String date, double sales, double profit, double wholesale, double balance, double deposit, double withdrawal, int transactionCount) {}
    public record TransactionRow(String time, String type, String productName, double quantity, double price, double total, double profit, String notes, String unitType, String supplierCode, String supplierName, String measurementUnit, String customerCode, String customerName, double packageWeight) {}
    public record ToolRow(int id, String code, String name, String size, String renterName, double rentalPrice, String notes, String status, String rentalDate, String createdAt) {}
    public record CashLedgerRow(String type, double amount, String time, String notes, String personName) {}
    public record SupplierDailyRecord(String time, String type, String productName, double quantity, double price, double total, String notes, String supplierCode, String supplierName, String supplierPhone, String unitType, double packageWeight) {}
    public record MonthlyReport(String yearMonth, double totalSales, double totalProfit, double totalWholesale, double totalDeposit, double totalWithdrawal, int totalTransactions, double closingBalance, int daysCount) {}
    public record YearlyReport(int year, double totalSales, double totalProfit, double totalWholesale, double totalDeposit, double totalWithdrawal, int totalTransactions, double closingBalance, int monthsCount) {}
    public record DailyReportArchive(int id, String reportDate, String snapshotData, int saleCount, int purchaseCount, int returnCount, double totalSales, double totalPurchases, double totalProfit, double netMovement) {
        public DailyReportArchive(int id, String reportDate, String snapshotData) {
            this(id, reportDate, snapshotData, 0, 0, 0, 0, 0, 0, 0);
        }
    }
    public record MonthlyReportArchive(int id, String yearMonth, String snapshotData, int daysCount) {}

    private static final String NOW = "datetime('now','localtime')";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final double PRICE_EPSILON = 1e-8;

    public int createProduct(Product p) { return createProduct(p, null, null, 0, 0); }

    public int createProduct(Product p, String companyName, String categoryName, double openingQuantity, double openingCost) {
        if (p == null || ValidationUtil.isNullOrEmpty(p.getName())) throw new ValidationException("اسم المنتج مطلوب");
        if (ValidationUtil.isNullOrEmpty(categoryName)) throw new ValidationException("اختر تصنيف المنتج");
        if (!Set.of(UnitType.COUNT.name(), UnitType.WEIGHT.name(), UnitType.PACKAGE.name()).contains(p.getUnitType())) throw new ValidationException("نوع الوحدة غير صحيح");
        if (p.getDefaultSellingPrice() <= 0) throw new ValidationException("سعر البيع يجب أن يكون أكبر من صفر");
        if (p.getDefaultWholesalePrice() <= 0) throw new ValidationException("سعر الشراء يجب أن يكون أكبر من صفر");
        if (openingQuantity < 0) throw new ValidationException("رصيد البداية غير صالح");
        if (openingQuantity > 0 && openingCost <= 0) throw new ValidationException("تكلفة رصيد البداية يجب أن تكون أكبر من صفر عندما يكون هناك رصيد افتتاحي");
        if (UnitType.COUNT.name().equals(p.getUnitType()) && openingQuantity != Math.rint(openingQuantity)) throw new ValidationException("رصيد منتج القطعة يجب أن يكون رقمًا صحيحًا");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                Integer companyId = ensureNamed(c, "Companies", companyName);
                int categoryId = ensureNamed(c, "Categories", categoryName);
                try (PreparedStatement duplicate = c.prepareStatement("SELECT p.name,p.size,coalesce(co.name,'') company FROM Products p LEFT JOIN Companies co ON co.id=p.company_id WHERE p.category_id=? AND p.is_active=1")) {
                    duplicate.setInt(1, categoryId);
                    try (ResultSet r = duplicate.executeQuery()) {
                        while (r.next())
                            if (normalize(r.getString("name")).equals(normalize(p.getName())) && normalize(r.getString("size")).equals(normalize(p.getSize())) && normalize(r.getString("company")).equals(normalize(companyName)))
                                throw new ValidationException("هذا المنتج مسجل بالفعل بنفس البيانات");
                    }
                }
                String code = nextCode(c, categoryName);
                try (PreparedStatement s = c.prepareStatement("INSERT INTO Products(code,company_id,category_id,name,size,unit_type,default_wholesale_price,default_selling_price,notes,measurement_unit,unit_name,created_at) VALUES(?,?,?,?,?,?,?,?,?,'','',datetime('now','localtime'))", Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1, code);
                    s.setObject(2, companyId);
                    s.setInt(3, categoryId);
                    s.setString(4, p.getName().trim());
                    s.setString(5, ValidationUtil.isNullOrEmpty(p.getSize()) ? "" : p.getSize().trim());
                    s.setString(6, p.getUnitType());
                    s.setDouble(7, openingQuantity > 0 ? openingCost : p.getDefaultWholesalePrice());
                    s.setDouble(8, p.getDefaultSellingPrice());
                    s.setString(9, ValidationUtil.isNullOrEmpty(p.getNotes()) ? "" : p.getNotes().trim());
                    s.executeUpdate();
                    try (ResultSet r = s.getGeneratedKeys()) {
                        r.next();
                        int id = r.getInt(1);
                        try (PreparedStatement st = c.prepareStatement("INSERT INTO Stock(product_id,total_quantity,unit_count) VALUES(?,0,0)")) { st.setInt(1, id); st.executeUpdate(); }
                        if (openingQuantity > 0) {
                            // For simple product creation from settings, treat as non-count product
                            boolean isCountProduct = false;
                            double batchCost = UnitType.WEIGHT.name().equals(p.getUnitType()) ? openingCost / 1000.0 : openingCost;
                            double purchaseAmount = computeSaleAmount(p.getUnitType(), openingQuantity, openingCost);
                            batch(c, id, openingQuantity, batchCost);
                            stockCount(c, id, openingQuantity, isCountProduct);
                            long tx = transaction(c, id, "IN", openingQuantity, openingCost, 0, "رصيد افتتاحي");
                            ledger(c, "PURCHASE_EXPENSE", purchaseAmount, tx, "رصيد افتتاحي");
                            recordDailyTransaction(c, tx, "شراء", p.getName().trim(), openingQuantity, openingCost, 0, "رصيد افتتاحي");
                            updateDailyCardPurchase(c, purchaseAmount);
                        }
                        c.commit();
                        return id;
                    }
                }
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    public String removeProduct(int productId) {
        if (productId <= 0) throw new ValidationException("اختر منتجًا أولاً");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (scalar(c, "SELECT count(*) FROM Products WHERE id=?", productId) == 0) throw new ValidationException("المنتج غير موجود");

                // Check if any quantity has been sold (OUT transactions)
                double soldQty = scalar(c, "SELECT coalesce(sum(quantity - coalesce(returned_quantity,0)),0) FROM Transactions WHERE product_id=? AND type='OUT'", productId);
                if (soldQty > PRICE_EPSILON) {
                    throw new ValidationException("لا يمكن حذف هذا المنتج لأنه تم البيع منه.");
                }

                // Get product info for refund calculation
                String productName = productNameById(c, productId);
                String unitType = productUnitType(c, productId);
                double remainingQty = scalar(c, "SELECT coalesce(total_quantity,0) + coalesce(unit_count,0) FROM Stock WHERE product_id=?", productId);
                double wholesalePrice = scalar(c, "SELECT coalesce(default_wholesale_price,0) FROM Products WHERE id=?", productId);
                double refundValue = computeSaleAmount(unitType, remainingQty, wholesalePrice);

                // Refund remaining stock value to treasury (before deleting the product, so we can reference its ID)
                if (refundValue > PRICE_EPSILON) {
                    String note = "مرتجع مشتريات حذف منتج: " + productName;
                    long txId = transaction(c, productId, "RETURN", 0, 0, 0, note);
                    ledger(c, "PURCHASE_EXPENSE", -refundValue, txId, note);
                    recordDailyTransaction(c, txId, "مرتجع مشتريات", productName, remainingQty, wholesalePrice, 0, note);
                    updateDailyCardWholesaleRefund(c, refundValue);
                }

                // Delete all related records in FK-safe order:
                //   1. Tables referencing Transactions (DailyTransactions, CashLedger)
                //   2. Tables referencing Products (Batches, Stock)
                //   3. Transactions + Price_History
                //   4. Products
                try (PreparedStatement s = c.prepareStatement("DELETE FROM DailyTransactions WHERE transaction_id IN (SELECT id FROM Transactions WHERE product_id=?)")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM CashLedger WHERE related_transaction_id IN (SELECT id FROM Transactions WHERE product_id=?)")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM Batches WHERE product_id=?")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM Stock WHERE product_id=?")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM Transactions WHERE product_id=?")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM Price_History WHERE product_id=?")) { s.setInt(1, productId); s.executeUpdate(); }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM Products WHERE id=?")) { s.setInt(1, productId); s.executeUpdate(); }

                c.commit();
                if (refundValue > PRICE_EPSILON) {
                    return "تم حذف المنتج وإضافة " + String.format("%.2f", refundValue) + " ج.م من قيمة المخزون إلى الخزينة.";
                }
                return "تم حذف المنتج نهائيًا.";
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    public void purchase(int id, double q, double wholesale, String note) {
        validate(id, q);
        wholesale = price(wholesale);
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                String productName = productNameById(c, id);
                String unitType = productUnitType(c, id);
                double batchCost = UnitType.WEIGHT.name().equals(unitType) ? wholesale / 1000.0 : wholesale;
                double purchaseAmount = computeSaleAmount(unitType, q, wholesale);
                batch(c, id, q, batchCost);
                stock(c, id, q);
                try (PreparedStatement s = c.prepareStatement("UPDATE Products SET default_wholesale_price=? WHERE id=?")) { s.setDouble(1, wholesale); s.setInt(2, id); s.executeUpdate(); }
                long t = transaction(c, id, "IN", q, wholesale, 0, note);
                ledger(c, "PURCHASE_EXPENSE", purchaseAmount, t, note);
                recordDailyTransaction(c, t, "شراء", productName, q, wholesale, 0, note);
                updateDailyCardPurchase(c, purchaseAmount);
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    public void purchaseMultiple(List<PurchaseItem> items) {
        if (items == null || items.isEmpty()) throw new ValidationException("لا توجد منتجات للشراء");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                double totalCost = 0;
                for (PurchaseItem item : items) {
                    String unitType = productUnitType(c, item.productId);
                    totalCost += computeSaleAmount(unitType, item.quantity, item.wholesale);
                }
                double balance = currentBalance(c);
                if (totalCost > balance + PRICE_EPSILON) {
                    throw new ValidationException("الرصيد غير كافٍ لإتمام عملية الشراء.");
                }
                for (PurchaseItem item : items) {
                    validate(item.productId, item.quantity);
                    double w = price(item.wholesale);
                    String productName = productNameById(c, item.productId);
                    String unitType = productUnitType(c, item.productId);
                    double batchCost = UnitType.WEIGHT.name().equals(unitType) ? w / 1000.0 : w;
                    double purchaseAmount = computeSaleAmount(unitType, item.quantity, w);
                    batch(c, item.productId, item.quantity, batchCost);
                    stock(c, item.productId, item.quantity);
                    try (PreparedStatement s = c.prepareStatement("UPDATE Products SET default_wholesale_price=?,default_selling_price=? WHERE id=?")) {
                        s.setDouble(1, w);
                        s.setDouble(2, item.selling);
                        s.setInt(3, item.productId);
                        s.executeUpdate();
                    }
                    long t = transaction(c, item.productId, "IN", item.quantity, w, 0, item.note);
                    ledger(c, "PURCHASE_EXPENSE", purchaseAmount, t, item.note);
                    recordDailyTransaction(c, t, "شراء", productName, item.quantity, w, 0, item.note);
                }
                updateDailyCardPurchase(c, totalCost);
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    public record PurchaseItem(int productId, String name, double quantity, double wholesale, double selling, String note) {}

public record IncomingItem(String company, String category, String name, double quantity, double wholesale, double selling, String unitType, double packageWeight, double taxAmount, String measurementUnit, String unitName, String supplierCode, String supplierName) {
    public IncomingItem(String company, String category, String name, double quantity, double wholesale, double selling) {
        this(company, category, name, quantity, wholesale, selling, UnitType.COUNT.name(), 0, 0, "", "", "", "");
    }
    public IncomingItem(String company, String category, String name, double quantity, double wholesale, double selling, String unitType, double packageWeight) {
        this(company, category, name, quantity, wholesale, selling, unitType, packageWeight, 0, "", "", "", "");
    }
}

    /**
     * Adds one or more new products to inventory in a single purchase operation.
     * Codes are generated automatically by category. The total purchase cost must be
     * covered by the available treasury balance (wholesale balance + profit balance).
     */
    public void addIncoming(List<IncomingItem> items) {
        if (items == null || items.isEmpty()) throw new ValidationException("لا توجد منتجات للإضافة");
        // Validate supplier code once for the whole operation
        String supplierCode = items.get(0).supplierCode();
        String supplierName = items.get(0).supplierName();
        Supplier sup = null;
        if (supplierCode != null && !supplierCode.trim().isEmpty()) {
            sup = supplierByCode(supplierCode.trim());
            if (sup == null) throw new ValidationException("كود المورد غير موجود في النظام.");
        } else {
            throw new ValidationException("يرجى إدخال كود المورد.");
        }
        for (IncomingItem it : items) {
            if (ValidationUtil.isNullOrEmpty(it.name())) throw new ValidationException("يرجى إدخال جميع البيانات.");
            if (ValidationUtil.isNullOrEmpty(it.category()))
                throw new ValidationException("يرجى إدخال جميع البيانات.");
            if (it.quantity() <= 0 || it.wholesale() <= 0 || it.selling() <= 0)
                throw new ValidationException("يرجى إدخال جميع البيانات.");
            if (!Set.of(UnitType.COUNT.name(), UnitType.WEIGHT.name(), UnitType.PACKAGE.name()).contains(it.unitType()))
                throw new ValidationException("نوع الوحدة غير صحيح");
            if (UnitType.COUNT.name().equals(it.unitType()) && it.quantity() != Math.rint(it.quantity()))
                throw new ValidationException("المنتج بالقطعة؛ الكمية يجب أن تكون رقمًا صحيحًا");
            if (UnitType.PACKAGE.name().equals(it.unitType()) && it.packageWeight() <= 0)
                throw new ValidationException("يجب تحديد وزن العبوة للمنتجات من نوع عبوة");
            if (UnitType.PACKAGE.name().equals(it.unitType()) && it.quantity() != Math.rint(it.quantity()))
                throw new ValidationException("عدد العبوات يجب أن يكون رقمًا صحيحًا");
            // Check that the supplier's specialty includes this category
            String supSpecialty = sup.specialty() != null ? sup.specialty().trim() : "";
            if (!supSpecialty.isEmpty()) {
                boolean specialtyMatches = false;
                for (String s : supSpecialty.split(",")) {
                    if (s.trim().equalsIgnoreCase(it.category())) {
                        specialtyMatches = true;
                        break;
                    }
                }
                if (!specialtyMatches) {
                    throw new ValidationException("الصنف المحدد ليس ضمن تخصص المورد.");
                }
            }
        }
        double totalCost = items.stream().mapToDouble(i ->
            UnitType.WEIGHT.name().equals(i.unitType()) ? (i.quantity() / 1000.0) * i.wholesale() : i.quantity() * i.wholesale()
        ).sum();
        double totalTax = items.stream().mapToDouble(IncomingItem::taxAmount).sum();
        double totalWithTax = totalCost + totalTax;
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                double available = currentBalance(c);
                if (totalWithTax > available + PRICE_EPSILON) throw new ValidationException("الرصيد غير كافٍ لإتمام عملية الشراء.");
                for (IncomingItem it : items) {
                    Integer companyId = ensureNamed(c, "Companies", it.company());
                    int categoryId = ensureNamed(c, "Categories", it.category());
                    int productId = findOrCreateProduct(c, companyId, categoryId, it);
                    // Save supplier info on the product
                    saveSupplierOnProduct(c, productId, sup.code(), sup.name());
                    boolean isCountProduct = isCountBasedProduct(it.unitType(), it.measurementUnit());
                    double batchCost = UnitType.WEIGHT.name().equals(it.unitType()) ? it.wholesale() / 1000.0 : it.wholesale();
                    batch(c, productId, it.quantity(), batchCost);
                    stockCount(c, productId, it.quantity(), isCountProduct);
                    // عند دمج منتج موجود: اعتماد السعر الأعلى لكل نوع
                    try (PreparedStatement s = c.prepareStatement("UPDATE Products SET default_wholesale_price = MAX(default_wholesale_price, ?), default_selling_price = MAX(default_selling_price, ?) WHERE id=?")) {
                        s.setDouble(1, it.wholesale());
                        s.setDouble(2, it.selling());
                        s.setInt(3, productId);
                        s.executeUpdate();
                    }
                    double purchaseAmount = UnitType.WEIGHT.name().equals(it.unitType()) ? (it.quantity() / 1000.0) * it.wholesale() : it.quantity() * it.wholesale();
                    double itemTax = it.taxAmount();
                    double totalItemAmount = purchaseAmount + itemTax;
                    long t = transaction(c, productId, "IN", it.quantity(), it.wholesale(), 0, "وارد");
                    // Record the full purchase expense (including tax if applicable) in the treasury ledger
                    ledger(c, "PURCHASE_EXPENSE", totalItemAmount, t, itemTax > 0 ? "وارد شامل ضريبة" : "وارد");
                    // Save the tax amount in the transaction record for separate tracking
                    if (itemTax > 0) {
                        try (PreparedStatement us = c.prepareStatement("UPDATE Transactions SET tax_amount=? WHERE id=?")) {
                            us.setDouble(1, itemTax);
                            us.setLong(2, t);
                            us.executeUpdate();
                        }
                    }
                    recordDailyTransaction(c, t, "شراء", it.name().trim(), it.quantity(), it.wholesale(), 0, "وارد", "", "", it.measurementUnit());
                }
                // Update daily card with the total cost (including tax if applicable)
                updateDailyCardPurchase(c, totalWithTax);
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /**
     * يبحث عن منتج مطابق حسب القواعد التالية:
     * 1- إذا تطابق: الصنف + الاسم + الشركة + سعة العبوة ← يُضاف للمنتج الحالي.
     * 2- إذا اختلف الاسم أو الشركة ← يُنشأ منتج جديد.
     * 3- إذا كانت الوحدة من نوع عبوة (زجاجة/جركن/جالون/عبوة/بستلة) واختلفت سعة العبوة ← يُنشأ منتج جديد.
     */
    private int findOrCreateProduct(Connection c, Integer companyId, int categoryId, IncomingItem it) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT id,name,coalesce((SELECT name FROM Companies WHERE id=Products.company_id),''),coalesce(package_weight,0) FROM Products WHERE category_id=? AND is_active=1")) {
            s.setInt(1, categoryId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (normalize(r.getString(2)).equals(normalize(it.name())) && normalize(r.getString(3)).equals(normalize(it.company()))) {
                        double existingPkgWeight = r.getDouble(4);
                        // التحقق من سعة العبوة:
                    // - إذا كان المنتج من نوع عبوة (PACKAGE): نقارن package_weight
                    // - إذا لم يكن من نوع عبوة: نتأكد أن كلاهما ليس عبوتين (newPkgWeight=0)
                    // - أي اختلاف في السعة حتى مع تطابق باقي البيانات = منتج جديد
                    double newPkgWeight = UnitType.PACKAGE.name().equals(it.unitType()) ? it.packageWeight() : 0;
                    if (Math.abs(existingPkgWeight - newPkgWeight) < 0.01) return r.getInt(1);
                    }
                }
            }
        }
        String code = nextCode(c, it.category());
        try (PreparedStatement s = c.prepareStatement("INSERT INTO Products(code,company_id,category_id,name,size,unit_type,default_wholesale_price,default_selling_price,package_weight,notes,measurement_unit,unit_name,created_at) VALUES(?,?,?,?,?,?,?,?,?,'',?,?,datetime('now','localtime'))", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, code);
            s.setObject(2, companyId);
            s.setInt(3, categoryId);
            s.setString(4, it.name().trim());
            s.setString(5, "");
            s.setString(6, it.unitType());
            s.setDouble(7, it.wholesale());
            s.setDouble(8, it.selling());
            s.setDouble(9, UnitType.PACKAGE.name().equals(it.unitType()) ? it.packageWeight() : 0);
            s.setString(10, it.measurementUnit() != null ? it.measurementUnit() : "");
            s.setString(11, it.unitName() != null ? it.unitName() : "");
            s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) {
                r.next();
                int id = r.getInt(1);
                try (PreparedStatement st = c.prepareStatement("INSERT INTO Stock(product_id,total_quantity,unit_count) VALUES(?,0,0)")) { st.setInt(1, id); st.executeUpdate(); }
                return id;
            }
        }
    }

    public void sell(int id, double q, double selling, String note) {
        validate(id, q);
        selling = price(selling);
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                double aggregate = scalar(c, "SELECT coalesce(total_quantity,0) + coalesce(unit_count,0) FROM Stock WHERE product_id=?", id);
                if (aggregate < q - PRICE_EPSILON)
                    throw new InsufficientStockException("الكمية المطلوبة أكبر من الرصيد المتاح");
                String productName = productNameById(c, id);
                String unitType = productUnitType(c, id);
                String mu = productMeasurementUnit(c, id);
                boolean isCountProduct = isCountBasedProduct(unitType, mu);
                double left = q, cost = 0;
                try (PreparedStatement s = c.prepareStatement("SELECT id,quantity_remaining,wholesale_price FROM Batches WHERE product_id=? AND quantity_remaining>0 ORDER BY date_in,id")) {
                    s.setInt(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next() && left > 0) {
                            double take = Math.min(left, r.getDouble(2));
                            try (PreparedStatement u = c.prepareStatement("UPDATE Batches SET quantity_remaining=quantity_remaining-? WHERE id=?")) { u.setDouble(1, take); u.setInt(2, r.getInt(1)); u.executeUpdate(); }
                            cost += take * r.getDouble(3);
                            left -= take;
                        }
                    }
                }
                stockCount(c, id, -q, isCountProduct);
                double saleAmount = computeSaleAmount(unitType, q, selling);
                double profit = Math.max(0, saleAmount - cost);
                long t = transaction(c, id, "OUT", q, selling, profit, note);
                ledger(c, "SALE_INCOME", saleAmount, t, note);
                recordDailyTransaction(c, t, "بيع", productName, q, selling, profit, note);
                updateDailyCardSale(c, saleAmount, profit);
                double remaining = scalar(c, "SELECT coalesce(total_quantity,0) + coalesce(unit_count,0) FROM Stock WHERE product_id=?", id);
                if (remaining < PRICE_EPSILON) {
                    try (PreparedStatement u = c.prepareStatement("UPDATE Products SET is_active=0 WHERE id=?")) { u.setInt(1, id); u.executeUpdate(); }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    public void sellMultiple(List<SaleItem> items) {
        sellMultipleWithCustomer(items, "", "");
    }

    public record SaleItem(int productId, String name, double quantity, double selling, String note, String unitType, double taxAmount) {
        public SaleItem(int productId, String name, double quantity, double selling, String note, String unitType) {
            this(productId, name, quantity, selling, note, unitType, 0);
        }
    }

    /** sellMultiple with customer tracking — records to DailyTransactions AND CustomerTransactions atomically. */
    public void sellMultipleWithCustomer(List<SaleItem> items, String customerCode, String customerName) {
        if (items == null || items.isEmpty()) throw new ValidationException("لا توجد منتجات للبيع");
        // ── Validate customer specialties against product categories ──
        if (customerCode != null && !customerCode.trim().isEmpty()) {
            Customer cust = customerByCode(customerCode.trim());
            if (cust == null) throw new ValidationException("العميل غير موجود في النظام.");
            String materials = cust.materials() != null ? cust.materials().trim() : "";
            if (materials.isEmpty()) {
                throw new ValidationException("هذا العميل ليس لديه أي أصناف أو تخصصات مسجلة، يرجى تحديث بيانات العميل أولًا.");
            }
            String[] allowedCategories = materials.split(",");
            // Query each product's category in a separate connection (before the main transaction)
            try (Connection tmp = DatabaseManager.getConnection()) {
                for (SaleItem item : items) {
                    String productCategory = "";
                    try (PreparedStatement s = tmp.prepareStatement("SELECT coalesce(g.name,'') FROM Products p LEFT JOIN Categories g ON g.id=p.category_id WHERE p.id=?")) {
                        s.setInt(1, item.productId);
                        try (ResultSet r = s.executeQuery()) {
                            if (r.next()) productCategory = r.getString(1);
                        }
                    }
                    boolean found = false;
                    for (String cat : allowedCategories) {
                        if (cat.trim().equalsIgnoreCase(productCategory)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new ValidationException("لا يمكن إتمام عملية البيع، لأن العميل غير مسجل للتعامل مع هذا الصنف: " + item.name);
                    }
                }
            } catch (SQLException e) { throw fail(e); }
        }
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                double totalSales = 0, totalProfit = 0, totalTax = 0;
                for (SaleItem item : items) {
                    validate(item.productId, item.quantity);
                    double sell = price(item.selling);
                    double aggregate = scalar(c, "SELECT coalesce(total_quantity,0) + coalesce(unit_count,0) FROM Stock WHERE product_id=?", item.productId);
                    if (aggregate < item.quantity - PRICE_EPSILON)
                        throw new InsufficientStockException("الكمية المطلوبة أكبر من الرصيد المتاح للمنتج: " + item.name);
                    String productName = productNameById(c, item.productId);
                    String unitType = productUnitType(c, item.productId);
                    String mu = productMeasurementUnit(c, item.productId);
                    boolean isCountProduct = isCountBasedProduct(unitType, mu);
                    double left = item.quantity, cost = 0;
                    try (PreparedStatement s = c.prepareStatement("SELECT id,quantity_remaining,wholesale_price FROM Batches WHERE product_id=? AND quantity_remaining>0 ORDER BY date_in,id")) {
                        s.setInt(1, item.productId);
                        try (ResultSet r = s.executeQuery()) {
                            while (r.next() && left > 0) {
                                double take = Math.min(left, r.getDouble(2));
                                try (PreparedStatement u = c.prepareStatement("UPDATE Batches SET quantity_remaining=quantity_remaining-? WHERE id=?")) { u.setDouble(1, take); u.setInt(2, r.getInt(1)); u.executeUpdate(); }
                                cost += take * r.getDouble(3);
                                left -= take;
                            }
                        }
                    }
                    stockCount(c, item.productId, -item.quantity, isCountProduct);
                    double saleAmount = computeSaleAmount(unitType, item.quantity, sell); // Pre-tax revenue
                    double itemTax = item.taxAmount();
                    double totalCollected = saleAmount + itemTax;
                    double profit = Math.max(0, saleAmount - cost); // Profit based on pre-tax amount
                    long t = transaction(c, item.productId, "OUT", item.quantity, sell, profit, item.note);
                    // Record pre-tax sale as revenue in treasury
                    ledger(c, "SALE_INCOME", saleAmount, t, item.note);
                    // Record VAT collected separately (not counted as revenue/profit)
                    if (itemTax > 0) {
                        ledger(c, "TAX_COLLECTED", itemTax, t, "ضريبة مبيعات");
                        try (PreparedStatement us = c.prepareStatement("UPDATE Transactions SET tax_amount=? WHERE id=?")) {
                            us.setDouble(1, itemTax);
                            us.setLong(2, t);
                            us.executeUpdate();
                        }
                    }
                    recordDailyTransaction(c, t, "بيع", productName, item.quantity, sell, profit, item.note, customerCode, customerName, mu);
                    // Record in CustomerTransactions atomically within the same transaction
                    if (customerCode != null && !customerCode.trim().isEmpty()) {
                        Customer cust = customerByCode(c, customerCode.trim());
                        if (cust != null) {
                            try (PreparedStatement cs = c.prepareStatement("INSERT INTO CustomerTransactions(customer_id,type,product_name,quantity,price,total,notes) VALUES(?,?,?,?,?,?,?)")) {
                                cs.setInt(1, cust.id());
                                cs.setString(2, "بيع");
                                cs.setString(3, productName != null ? productName : "");
                                cs.setDouble(4, item.quantity);
                                cs.setDouble(5, sell);
                                cs.setDouble(6, totalCollected);
                                cs.setString(7, item.note != null ? item.note : "");
                                cs.executeUpdate();
                            }
                        }
                    }
                    totalSales += saleAmount;
                    totalProfit += profit;
                    totalTax += itemTax;
                    double remaining = scalar(c, "SELECT coalesce(total_quantity,0) + coalesce(unit_count,0) FROM Stock WHERE product_id=?", item.productId);
                    if (remaining < PRICE_EPSILON) {
                        try (PreparedStatement u = c.prepareStatement("UPDATE Products SET is_active=0 WHERE id=?")) { u.setInt(1, item.productId); u.executeUpdate(); }
                    }
                }
                // Update daily card with total collected (including tax) for treasury tracking
                updateDailyCardSale(c, totalSales + totalTax, totalProfit);
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Update product wholesale and selling prices */
    public void updateProductPrices(int productId, double wholesalePrice, double sellingPrice) {
        if (wholesalePrice <= 0 || sellingPrice <= 0) throw new ValidationException("الأسعار يجب أن تكون أكبر من صفر");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE Products SET default_wholesale_price=?, default_selling_price=? WHERE id=?")) {
                s.setDouble(1, wholesalePrice);
                s.setDouble(2, sellingPrice);
                s.setInt(3, productId);
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Get customer code for a specific transaction from DailyTransactions. */
    public String getCustomerCodeForTransaction(long transactionId) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT coalesce(customer_code,'') FROM DailyTransactions WHERE transaction_id=? AND customer_code != '' LIMIT 1")) {
            s.setLong(1, transactionId);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return r.getString(1);
            }
        } catch (SQLException e) { throw fail(e); }
        return "";
    }

    /** Get customer transactions for a specific date (for PDF reports). */
    /** Get daily transactions that have supplier data, for the given date. */
    public List<SupplierDailyRecord> getSupplierTransactionsForDate(LocalDate date) {
        String d = date.format(DATE_FMT);
        List<SupplierDailyRecord> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,dt.product_name,dt.quantity,dt.price,dt.profit,coalesce(dt.notes,''),"
            + "coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(sup.phone,''),coalesce(p.unit_type,'COUNT'),coalesce(p.package_weight,0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "LEFT JOIN Suppliers sup ON sup.code=p.supplier_code "
            + "WHERE dc.card_date=? AND p.supplier_code IS NOT NULL AND p.supplier_code != '' AND dt.type != 'بيع' "
            + "ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(11);
                    double packageWeight = r.getDouble(12);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new SupplierDailyRecord(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getString(7), r.getString(8), r.getString(9), r.getString(10), unitType, packageWeight));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<CustomerTransaction> getCustomerTransactionsForDate(LocalDate date) {
        List<CustomerTransaction> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT ct.id,ct.type,ct.product_name,ct.quantity,ct.price,ct.total,ct.date_time,coalesce(ct.notes,''),coalesce(cu.name,''),coalesce(cu.customer_type,''),coalesce(cu.phone,'')"
            + " FROM CustomerTransactions ct JOIN Customers cu ON cu.id=ct.customer_id "
            + "WHERE date(ct.date_time)=? ORDER BY ct.date_time")) {
            s.setString(1, date.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new CustomerTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public void returnSale(long saleTransactionId, double quantity, String note) {
        returnSaleWithCustomer(saleTransactionId, quantity, note, "");
    }

    /** returnSale with optional customer tracking — atomic within one transaction */
    public void returnSaleWithCustomer(long saleTransactionId, double quantity, String note, String customerCode) {
        if (quantity <= 0) throw new ValidationException("أدخل كمية مرتجعة صحيحة");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("SELECT product_id,quantity,price,profit,returned_quantity FROM Transactions WHERE id=? AND type='OUT'")) {
                s.setLong(1, saleTransactionId);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new ValidationException("اختر عملية بيع صحيحة من القائمة");
                    int productId = r.getInt("product_id");
                    double sold = r.getDouble("quantity"), salePrice = r.getDouble("price"), returned = r.getDouble("returned_quantity");
                    double originalProfit = r.getDouble("profit");
                    if (quantity > sold - returned + PRICE_EPSILON) throw new ValidationException("الكمية المرتجعة أكبر من الكمية المتبقية لهذه الفاتورة");
                    validateForReturn(c, productId, quantity);
                    String unitType = productUnitType(c, productId);
                    double originalSaleAmount = computeSaleAmount(unitType, sold, salePrice);
                    double unitCost = (originalSaleAmount - originalProfit) / sold;
                    double returnSaleAmount = computeSaleAmount(unitType, quantity, salePrice);
                    double balance = currentBalance(c);
                    if (balance < returnSaleAmount - PRICE_EPSILON) throw new ValidationException("لا يوجد رصيد كافي");
                    batch(c, productId, quantity, unitCost);
                    String returnMu = productMeasurementUnit(c, productId);
                    stockCount(c, productId, quantity, isCountBasedProduct(unitType, returnMu));
                    double returnProfit = -(returnSaleAmount - quantity * unitCost);
                    long returnId = transaction(c, productId, "RETURN", quantity, salePrice, returnProfit, note);
                    try (PreparedStatement u = c.prepareStatement("UPDATE Transactions SET returned_quantity=returned_quantity+? WHERE id=?")) { u.setDouble(1, quantity); u.setLong(2, saleTransactionId); u.executeUpdate(); }
                    ledger(c, "SALE_RETURN", returnSaleAmount, returnId, note);
                    String productName = productNameById(c, productId);
                    recordDailyTransaction(c, returnId, "مرتجع", productName, quantity, salePrice, returnProfit, note);
                    updateDailyCardReturn(c, returnSaleAmount, Math.abs(returnProfit));
                    try (PreparedStatement u = c.prepareStatement("UPDATE Products SET is_active=1 WHERE id=? AND is_active=0")) { u.setInt(1, productId); u.executeUpdate(); }
                    // Record in CustomerTransactions atomically within same transaction
                    if (customerCode != null && !customerCode.trim().isEmpty()) {
                        Customer cust = customerByCode(c, customerCode.trim());
                        if (cust != null) {
                            double returnTotal = computeSaleAmount(unitType, quantity, salePrice);
                            try (PreparedStatement cs = c.prepareStatement("INSERT INTO CustomerTransactions(customer_id,type,product_name,quantity,price,total,notes) VALUES(?,?,?,?,?,?,?)")) {
                                cs.setInt(1, cust.id());
                                cs.setString(2, "مرتجع");
                                cs.setString(3, productName != null ? productName : "");
                                cs.setDouble(4, quantity);
                                cs.setDouble(5, salePrice);
                                cs.setDouble(6, returnTotal);
                                cs.setString(7, note != null ? note : "");
                                cs.executeUpdate();
                            }
                        }
                    }
                    c.commit();
                }
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /**
     * Execute a cash operation (DEPOSIT or WITHDRAWAL).
     * @return null if deposit, or a warning message if withdrawal exceeded profit.
     */
    public String cash(String type, double amount, String note, String personName) {
        if (amount <= 0) throw new ValidationException("المبلغ يجب أن يكون أكبر من صفر");
        if (!Set.of("DEPOSIT", "WITHDRAWAL").contains(type)) throw new ValidationException("نوع الخزينة غير صحيح");
        if (personName == null || personName.trim().isEmpty()) throw new ValidationException("يرجى إدخال اسم المودع / الساحب");
        if (note == null) note = "";
        String pn = personName.trim();
        final boolean[] profitInsufficient = {false};
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                if ("WITHDRAWAL".equals(type)) {
                    double balance = currentBalance(c);
                    if (balance < amount - PRICE_EPSILON) throw new ValidationException("لا يوجد رصيد كافي");
                    if (note.trim().isEmpty()) throw new ValidationException("يرجى إدخال الملاحظات");
                }
                long txId = cashTransaction(c, type, amount, note);
                ledger(c, type, amount, txId, note, pn);
                if ("DEPOSIT".equals(type)) {
                    recordDailyTransaction(c, txId, "إيداع", null, 0, amount, 0, note);
                    updateDailyCardDeposit(c, amount);
                } else {
                    recordDailyTransaction(c, txId, "سحب", null, 0, amount, 0, note);
                    profitInsufficient[0] = updateDailyCardWithdrawalDeduction(c, amount);
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
        return profitInsufficient[0] ? "الربح غير كافٍ لخصم المبلغ بالكامل منه، تم خصم المبلغ المتبقي من رأس المال (الجملة)." : null;
    }

    private static long cashTransaction(Connection c, String type, double amount, String note) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO Transactions(product_id,type,quantity,price,profit,date_time,notes) VALUES(NULL,?,0,?,0," + NOW + ",?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, type);
            s.setDouble(2, amount);
            s.setString(3, note);
            s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    private void stockCount(Connection c, int productId, double delta, boolean isCountProduct) throws SQLException {
        if (isCountProduct) {
            try (PreparedStatement s = c.prepareStatement("UPDATE Stock SET unit_count = coalesce(unit_count,0) + ? WHERE product_id=?")) {
                s.setDouble(1, delta);
                s.setInt(2, productId);
                s.executeUpdate();
            }
        } else {
            try (PreparedStatement s = c.prepareStatement("UPDATE Stock SET total_quantity = total_quantity + ? WHERE product_id=?")) {
                s.setDouble(1, delta);
                s.setInt(2, productId);
                s.executeUpdate();
            }
        }
    }

    private static boolean isCountBasedProduct(String unitType, String measurementUnit) {
        return UnitType.COUNT.name().equals(unitType) && "عدد".equals(measurementUnit);
    }

    public ProductRow productByCode(String code) {
        if (code == null || code.isBlank()) throw new ValidationException("أدخل كود المنتج");
        return products("").stream().filter(p -> p.code().equalsIgnoreCase(code.trim())).findFirst().orElseThrow(() -> new ValidationException("هذا المنتج غير موجود في الأصناف، يرجى إضافته أولًا من صفحة الأصناف."));
    }

    public List<ToolRow> allTools() {
        List<ToolRow> out = new ArrayList<>();
        String sql = "SELECT id,coalesce(code,''),name,size,renter_name,rental_price,notes,coalesce(status,'جاهزة'),coalesce(rental_date,''),created_at FROM Tools ORDER BY id DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            while (r.next()) out.add(new ToolRow(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10)));
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public void addTool(String name, String size, String renterName, double rentalPrice, String notes) {
        if (ValidationUtil.isNullOrEmpty(name)) throw new ValidationException("اسم العدة مطلوب");
        if (rentalPrice <= 0) throw new ValidationException("سعر التأجير يجب أن يكون أكبر من صفر");
        try (Connection c = DatabaseManager.getConnection()) {
            String code = nextToolCode(c);
            try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO Tools(code,name,size,renter_name,rental_price,notes) VALUES(?,?,?,?,?,?)")) {
                s.setString(1, code);
                s.setString(2, name.trim());
                s.setString(3, size != null ? size.trim() : "");
                s.setString(4, renterName != null ? renterName.trim() : "");
                s.setDouble(5, rentalPrice);
                s.setString(6, notes != null ? notes.trim() : "");
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    public void editTool(int toolId, String name, String size, double rentalPrice) {
        if (ValidationUtil.isNullOrEmpty(name)) throw new ValidationException("اسم العدة مطلوب");
        if (rentalPrice <= 0) throw new ValidationException("سعر التأجير يجب أن يكون أكبر من صفر");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(
                "UPDATE Tools SET name=?, size=?, rental_price=? WHERE id=?")) {
                s.setString(1, name.trim());
                s.setString(2, size != null ? size.trim() : "");
                s.setDouble(3, rentalPrice);
                s.setInt(4, toolId);
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    public void deleteTool(int toolId) {
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM Tools WHERE id=?")) {
                s.setInt(1, toolId);
                int deleted = s.executeUpdate();
                if (deleted == 0) throw new ValidationException("العدة غير موجودة");
            }
        } catch (SQLException e) { throw fail(e); }
    }

    public void unrentTool(int toolId) {
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(
                "UPDATE Tools SET status='جاهزة', renter_name='' WHERE id=?")) {
                s.setInt(1, toolId);
                int updated = s.executeUpdate();
                if (updated == 0) throw new ValidationException("العدة غير موجودة");
            }
        } catch (SQLException e) { throw fail(e); }
    }

    public void markToolAsRented(int toolId, double rentalPrice, String renterName) {
        if (rentalPrice <= 0) throw new ValidationException("سعر التأجير غير صالح");
        if (ValidationUtil.isNullOrEmpty(renterName)) throw new ValidationException("اسم المستأجر مطلوب");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Get tool name and code (read-only, not price or renter)
                String toolName = "";
                String toolCode = "";
                try (PreparedStatement s = c.prepareStatement("SELECT name,code FROM Tools WHERE id=?")) {
                    s.setInt(1, toolId);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            toolName = r.getString(1);
                            toolCode = r.getString(2) != null ? r.getString(2) : "";
                        }
                    }
                }

                String displayName = toolCode.isEmpty() ? toolName : toolName + " (" + toolCode + ")";
                String note = "تأجير عدة: " + displayName;
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

                // 1. Update tool status, rental date, and renter name
                try (PreparedStatement s = c.prepareStatement(
                    "UPDATE Tools SET status='تم تأجيرها', rental_date=?, renter_name=? WHERE id=?")) {
                    s.setString(1, now);
                    s.setString(2, renterName);
                    s.setInt(3, toolId);
                    s.executeUpdate();
                }

                // 2. Create a transaction record
                long txId;
                try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO Transactions(product_id,type,quantity,price,profit,date_time,notes) VALUES(NULL,'TOOL_RENTAL',0,?,0," + NOW + ",?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    s.setDouble(1, rentalPrice);
                    s.setString(2, note);
                    s.executeUpdate();
                    try (ResultSet r = s.getGeneratedKeys()) { r.next(); txId = r.getLong(1); }
                }

                // 3. Add to cash ledger (income)
                try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO CashLedger(type,amount,date_time,related_transaction_id,notes) VALUES('TOOL_RENTAL',?," + NOW + ",?,?)")) {
                    s.setDouble(1, rentalPrice);
                    s.setLong(2, txId);
                    s.setString(3, note);
                    s.executeUpdate();
                }

                // 4. Update daily card: add to sales + profit (rental income = pure profit)
                updateDailyCardSale(c, rentalPrice, rentalPrice);

                // 5. Record in DailyTransactions with renter name as customer_name (not in notes)
                recordDailyTransaction(c, txId, "تأجير", displayName, 0, rentalPrice, rentalPrice, "", "", renterName, "");

                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Get today's rental transactions for the homepage card. */
    public record TodayRental(String toolName, String toolCode, String time, double price) {}

    public List<TodayRental> getTodayRentals() {
        String today = LocalDate.now().format(DATE_FMT);
        List<TodayRental> out = new ArrayList<>();
        String sql = "SELECT dt.product_name,dt.price,dt.time_stamp"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id"
            + " WHERE dc.card_date=? AND dt.type='تأجير' ORDER BY dt.time_stamp DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, today);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    double price = r.getDouble(2);
                    String time = r.getString(3);
                    // Extract tool code from display name "دريل (TL0001)" if present
                    String code = "";
                    if (name != null && name.contains("(") && name.endsWith(")")) {
                        int start = name.lastIndexOf('(');
                        code = name.substring(start + 1, name.length() - 1);
                        name = name.substring(0, start).trim();
                    }
                    out.add(new TodayRental(name != null ? name : "", code, time != null ? time : "", price));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public record RentalRecord(String toolName, String toolCode, double price, String date, String time, String renterName, String size, String status) {}

    public List<RentalRecord> getAllRentals() {
        List<RentalRecord> out = new ArrayList<>();
        // Extract tool code from product_name "دريل (TL0001)" and LEFT JOIN with Tools for size + status
        // Renter name now stored in customer_name column (fallback to parsing notes for old records)
        String sql = "SELECT dt.product_name,dt.price,dc.card_date,dt.time_stamp,dt.notes,dt.customer_name,"
            + " COALESCE(t.size,''), COALESCE(t.status,'')"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id"
            + " LEFT JOIN Tools t ON t.code = SUBSTR(dt.product_name, INSTR(dt.product_name, '(') + 1,"
            + "  LENGTH(dt.product_name) - INSTR(dt.product_name, '(') - 1)"
            + " WHERE dt.type='تأجير' ORDER BY dc.card_date DESC, dt.time_stamp DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    double price = r.getDouble(2);
                    String date = r.getString(3);
                    String time = r.getString(4);
                    String notes = r.getString(5);
                    String customerName = r.getString(6);
                    String size = r.getString(7);
                    String status = r.getString(8);
                    // Renter name from customer_name (new records), fallback to parsing notes (old records)
                    String renterName = "";
                    if (customerName != null && !customerName.isEmpty()) {
                        renterName = customerName;
                    } else if (notes != null && notes.startsWith("مستأجر: ")) {
                        renterName = notes.substring(8).trim();
                    }
                    // Extract tool code from display name "دريل (TL0001)" if present
                    String code = "";
                    if (name != null && name.contains("(") && name.endsWith(")")) {
                        int start = name.lastIndexOf('(');
                        code = name.substring(start + 1, name.length() - 1);
                        name = name.substring(0, start).trim();
                    }
                    out.add(new RentalRecord(name != null ? name : "", code, price, date != null ? date : "", time != null ? time : "", renterName, size != null ? size : "", status != null ? status : ""));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    private static String nextToolCode(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT coalesce(max(cast(substr(code,3) as integer)),0) FROM Tools WHERE code LIKE 'TL%'")) {
            try (ResultSet r = s.executeQuery()) {
                int next = r.next() ? r.getInt(1) + 1 : 1;
                return String.format("TL%04d", next);
            }
        }
    }

    public String getLastPurchaseDate(int productId) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT date_time FROM Transactions WHERE product_id=? AND type='IN' ORDER BY date_time DESC LIMIT 1")) {
            s.setInt(1, productId);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String dt = r.getString(1);
                    if (dt != null && dt.length() >= 10) return dt.substring(0, 10);
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    public List<ProductRow> productsForSale(String filter) {
        String x = "%" + (filter == null ? "" : filter.trim()) + "%";
        List<ProductRow> out = new ArrayList<>();
        String sql = "SELECT p.id,p.code,p.name,coalesce(c.name,''),coalesce(g.name,''),p.unit_type,coalesce(s.total_quantity,0),p.default_selling_price,p.default_wholesale_price,coalesce(p.package_weight,0),coalesce(p.notes,''),coalesce(p.measurement_unit,''),coalesce(p.unit_name,''),coalesce(s.unit_count,0),coalesce(p.created_at,''),"
            + "coalesce(p.supplier_code,''),coalesce(sup.name,p.supplier_name,''),coalesce(sup.phone,'')"
            + " FROM Products p LEFT JOIN Companies c ON c.id=p.company_id LEFT JOIN Categories g ON g.id=p.category_id LEFT JOIN Stock s ON s.product_id=p.id LEFT JOIN Suppliers sup ON sup.code=p.supplier_code WHERE p.is_active=1 AND (p.code LIKE ? COLLATE NOCASE) ORDER BY p.code COLLATE NOCASE";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, x);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new ProductRow(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getDouble(10), r.getString(11), r.getString(12), r.getString(13), r.getDouble(14), r.getString(15), r.getString(16), r.getString(17), r.getString(18)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<ProductRow> products(String filter) {
        String x = "%" + (filter == null ? "" : filter.trim()) + "%";
        List<ProductRow> out = new ArrayList<>();
        String sql = "SELECT p.id,p.code,p.name,coalesce(c.name,''),coalesce(g.name,''),p.unit_type,coalesce(s.total_quantity,0),p.default_selling_price,p.default_wholesale_price,coalesce(p.package_weight,0),coalesce(p.notes,''),coalesce(p.measurement_unit,''),coalesce(p.unit_name,''),coalesce(s.unit_count,0),coalesce(p.created_at,''),"
            + "coalesce(p.supplier_code,''),coalesce(sup.name,p.supplier_name,''),coalesce(sup.phone,'')"
            + " FROM Products p LEFT JOIN Companies c ON c.id=p.company_id LEFT JOIN Categories g ON g.id=p.category_id LEFT JOIN Stock s ON s.product_id=p.id LEFT JOIN Suppliers sup ON sup.code=p.supplier_code WHERE p.is_active=1 AND (p.code LIKE ? COLLATE NOCASE) ORDER BY p.code COLLATE NOCASE";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, x);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new ProductRow(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getDouble(10), r.getString(11), r.getString(12), r.getString(13), r.getDouble(14), r.getString(15), r.getString(16), r.getString(17), r.getString(18)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<SaleRow> recentSales() {
        List<SaleRow> out = new ArrayList<>();
        String sql = "SELECT t.id,p.code,p.name,t.quantity,t.price,t.returned_quantity,t.date_time,coalesce(t.notes,''),coalesce(dt.customer_code,''),coalesce(dt.customer_name,'')"
            + " FROM Transactions t JOIN Products p ON p.id=t.product_id"
            + " LEFT JOIN (SELECT transaction_id,customer_code,customer_name FROM DailyTransactions WHERE customer_code != '') dt ON dt.transaction_id=t.id"
            + " WHERE t.type='OUT' AND t.quantity>t.returned_quantity ORDER BY t.date_time DESC,t.id DESC LIMIT 100";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            while (r.next()) out.add(new SaleRow(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10)));
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public Dashboard dashboard() {
        try (Connection c = DatabaseManager.getConnection()) {
            DailyCard today = ensureTodayCardInternal(c);
            return new Dashboard(
                (int) scalar(c, "SELECT count(*) FROM Products WHERE is_active=1", null),
                scalar(c, "SELECT coalesce(sum(quantity_remaining*wholesale_price),0) FROM Batches", null),
                today.sales(),
                today.wholesale(),
                today.profit(),
                (int) scalar(c, "SELECT count(*) FROM Stock WHERE coalesce(total_quantity,0) + coalesce(unit_count,0) <= 0", null)
            );
        } catch (SQLException e) { throw fail(e); }
    }

    // ========== Daily Cards ==========

    public DailyCard ensureTodayCard() {
        String today = LocalDate.now().format(DATE_FMT);
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date=?")) {
                s.setString(1, today);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) return cardFromResultSet(r);
                }
            }
            // New day — carry forward the actual cash balance from CashLedger (not zero!)
            double actualBalance = currentBalance(c);
            try (PreparedStatement s = c.prepareStatement("INSERT INTO DailyCards(card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed) VALUES(?,0,0,0,?,0,0,0,0)", Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, today);
                s.setDouble(2, actualBalance);
                s.executeUpdate();
                try (ResultSet r = s.getGeneratedKeys()) {
                    r.next();
                    return new DailyCard(r.getInt(1), today, 0, 0, 0, actualBalance, 0, 0, 0, false);
                }
            }
        } catch (SQLException e) { throw fail(e); }
    }

    public List<DailyCard> currentMonthCards() {
        String monthStart = LocalDate.now().withDayOfMonth(1).format(DATE_FMT);
        List<DailyCard> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date>=? ORDER BY card_date DESC")) {
            s.setString(1, monthStart);
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(cardFromResultSet(r)); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<DailyTransaction> todayTransactions() {
        DailyCard today = ensureTodayCard();
        return transactionsForCard(today.id());
    }    public List<DailyTransaction> transactionsForCard(int cardId) {
        List<DailyTransaction> out = new ArrayList<>();
        String sql = "SELECT dt.id,dt.type,coalesce(dt.product_name,''),dt.quantity,dt.price,dt.profit,dt.time_stamp,coalesce(dt.notes,''),"
            + "coalesce(dt.customer_code,''),coalesce(dt.customer_name,''),"
            + "coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),"
            + "coalesce(dt.measurement_unit,''),"
            + "coalesce(p.unit_type,'COUNT'),coalesce(p.package_weight,0)"
            + " FROM DailyTransactions dt"
            + " LEFT JOIN Transactions t ON t.id=dt.transaction_id"
            + " LEFT JOIN Products p ON p.id=t.product_id"
            + " WHERE dt.card_id=? ORDER BY dt.time_stamp DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, cardId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new DailyTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getString(14), r.getDouble(15)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Close today's card, transfer balance to tomorrow, and handle month/year transitions.
     * Called at midnight by the scheduler.
     * <p>
     * IMPORTANT: The cash balance is read directly from the CashLedger table (the single
     * source of truth) so it never gets reset or miscalculated at day boundaries.
     * Only daily statistics (sales, profit, wholesale, deposit, withdrawal) are reset to zero
     * for the new day — the cash balance itself persists unchanged.
     */
    public void closeDayAndStartNew() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        String todayStr = today.format(DATE_FMT);
        String tomorrowStr = tomorrow.format(DATE_FMT);

        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // ── Get today's DailyCard before closing to calculate the daily net ──
                double todaySales = 0, todayWholesale = 0, todayDeposit = 0, todayWithdrawal = 0;
                try (PreparedStatement s = c.prepareStatement("SELECT coalesce(sales,0),coalesce(wholesale,0),coalesce(deposit,0),coalesce(withdrawal,0) FROM DailyCards WHERE card_date=?")) {
                    s.setString(1, todayStr);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            todaySales = r.getDouble(1);
                            todayWholesale = r.getDouble(2);
                            todayDeposit = r.getDouble(3);
                            todayWithdrawal = r.getDouble(4);
                        }
                    }
                }

                // ── Calculate today's net balance (the result of today's operations) ──
                // sale revenue + deposits - purchase costs - withdrawals  (الربح مضمن في sales أصلاً)
                double todayNet = todaySales - todayWholesale + todayDeposit - todayWithdrawal;

                // ── Record the daily rollover in CashLedger as an explicit transfer ──
                try (PreparedStatement s = c.prepareStatement("INSERT INTO CashLedger(type,amount,date_time,notes) VALUES('DAILY_ROLLOVER',?," + NOW + ",'ترحيل رصيد اليوم (الخزينة)')")) {
                    s.setDouble(1, todayNet);
                    s.executeUpdate();
                }

                // ── Close today's card ──
                try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET is_closed=1 WHERE card_date=? AND is_closed=0")) {
                    s.setString(1, todayStr);
                    s.executeUpdate();
                }

                // ── Get the actual cash balance from CashLedger (single source of truth) ──
                double actualBalance = currentBalance(c);

                // ── Update today's card with the actual closing balance ──
                try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET balance=? WHERE card_date=?")) {
                    s.setDouble(1, actualBalance);
                    s.setString(2, todayStr);
                    s.executeUpdate();
                }

                // ── Create tomorrow's card with balance = actual cash balance (not zero!) ──
                try (PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO DailyCards(card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed) VALUES(?,0,0,0,?,0,0,0,0)")) {
                    s.setString(1, tomorrowStr);
                    s.setDouble(2, actualBalance);
                    s.executeUpdate();
                }

                // ── Save daily report snapshot before starting a new day ──
                saveDailyReportSnapshot(c, today);

                // ── Month-end: generate monthly report if tomorrow starts a new month ──
                if (tomorrow.getDayOfMonth() == 1) {
                    generateMonthlyReportInternal(c, today);
                }

                // ── Year-end: generate yearly report if tomorrow starts a new year ──
                if (tomorrow.getDayOfYear() == 1) {
                    generateYearlyReportInternal(c, today.getYear());
                }

                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Save a complete daily report snapshot before closing the day. */
    private void saveDailyReportSnapshot(Connection c, LocalDate date) throws SQLException {
        String d = date.format(DATE_FMT);
        // ── Duplicate check: skip if snapshot already exists for this date ──
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM DailyReportArchives WHERE report_date=?")) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) { if (r.next()) return; }
        }
        // 1. Get the DailyCard
        DailyCard card = null;
        try (PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date=?")) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    card = new DailyCard(r.getInt(1), r.getString(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getInt(9), r.getInt(10) == 1);
                }
            }
        }
        if (card == null) return; // No data for this day

        // 2. Get DailyTransactions for this card
        List<DailyTransaction> txs = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT dt.id,dt.type,coalesce(dt.product_name,''),dt.quantity,dt.price,dt.profit,dt.time_stamp,coalesce(dt.notes,''),coalesce(dt.customer_code,''),coalesce(dt.customer_name,''),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(dt.measurement_unit,'') FROM DailyTransactions dt LEFT JOIN Transactions t ON t.id=dt.transaction_id LEFT JOIN Products p ON p.id=t.product_id WHERE dt.card_id=? ORDER BY dt.time_stamp")) {
            s.setInt(1, card.id());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    txs.add(new DailyTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getString(14), r.getDouble(15)));
                }
            }
        }

        // 3. Get CustomerTransactions for this date
        List<CustomerTransaction> custTxs = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT ct.id,ct.type,coalesce(ct.product_name,''),ct.quantity,ct.price,ct.total,ct.date_time,coalesce(ct.notes,''),coalesce(cu.name,''),coalesce(cu.customer_type,''),coalesce(cu.phone,'') FROM CustomerTransactions ct JOIN Customers cu ON cu.id=ct.customer_id WHERE date(ct.date_time)=? ORDER BY ct.date_time")) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    custTxs.add(new CustomerTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11)));
                }
            }
        }

        // 4. Get supplier transactions for this date (for completeness)
        List<java.util.Map<String, String>> supplierTxs = new java.util.ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT dt.time_stamp,coalesce(dt.type,''),coalesce(dt.product_name,''),dt.quantity,dt.price,dt.profit,coalesce(dt.notes,''),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(sup.phone,'') FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id LEFT JOIN Transactions t ON t.id=dt.transaction_id LEFT JOIN Products p ON p.id=t.product_id LEFT JOIN Suppliers sup ON sup.code=p.supplier_code WHERE dc.card_date=? AND p.supplier_code IS NOT NULL AND p.supplier_code != '' AND dt.type != 'بيع' ORDER BY dt.time_stamp")) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    java.util.Map<String, String> m = new java.util.HashMap<>();
                    m.put("time", r.getString(1));
                    m.put("type", r.getString(2));
                    m.put("product_name", r.getString(3));
                    m.put("quantity", String.valueOf(r.getDouble(4)));
                    m.put("price", String.valueOf(r.getDouble(5)));
                    m.put("profit", String.valueOf(r.getDouble(6)));
                    m.put("notes", r.getString(7));
                    m.put("supplier_code", r.getString(8));
                    m.put("supplier_name", r.getString(9));
                    m.put("supplier_phone", r.getString(10));
                    supplierTxs.add(m);
                }
            }
        }

        // 5. Build JSON snapshot manually
        StringBuilder json = new StringBuilder();
        json.append("{\"card\":{");
        json.append("\"date\":\"").append(escapeJson(card.date())).append("\",");
        json.append("\"sales\":").append(card.sales()).append(",");
        json.append("\"profit\":").append(card.profit()).append(",");
        json.append("\"wholesale\":").append(card.wholesale()).append(",");
        json.append("\"balance\":").append(card.balance()).append(",");
        json.append("\"deposit\":").append(card.deposit()).append(",");
        json.append("\"withdrawal\":").append(card.withdrawal()).append(",");
        json.append("\"transaction_count\":").append(card.transactionCount());
        json.append("},\"transactions\":[");
        boolean first = true;
        for (DailyTransaction tx : txs) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"type\":\"").append(escapeJson(tx.type())).append("\",");
            json.append("\"product_name\":\"").append(escapeJson(tx.productName() != null ? tx.productName() : "")).append("\",");
            json.append("\"quantity\":").append(tx.quantity()).append(",");
            json.append("\"price\":").append(tx.price()).append(",");
            json.append("\"profit\":").append(tx.profit()).append(",");
            json.append("\"time\":\"").append(escapeJson(tx.time())).append("\",");
            json.append("\"notes\":\"").append(escapeJson(tx.notes())).append("\",");
            json.append("\"customer_name\":\"").append(escapeJson(tx.customerName())).append("\",");
            json.append("\"customer_code\":\"").append(escapeJson(tx.customerCode())).append("\",");
            json.append("\"supplier_code\":\"").append(escapeJson(tx.supplierCode())).append("\",");
            json.append("\"supplier_name\":\"").append(escapeJson(tx.supplierName())).append("\",");
            json.append("\"measurement_unit\":\"").append(escapeJson(tx.measurementUnit())).append("\"}");
        }
        json.append("],\"customer_transactions\":[");
        first = true;
        for (CustomerTransaction ct : custTxs) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"type\":\"").append(escapeJson(ct.type())).append("\",");
            json.append("\"product_name\":\"").append(escapeJson(ct.productName() != null ? ct.productName() : "")).append("\",");
            json.append("\"quantity\":").append(ct.quantity()).append(",");
            json.append("\"price\":").append(ct.price()).append(",");
            json.append("\"total\":").append(ct.total()).append(",");
            json.append("\"date_time\":\"").append(escapeJson(ct.dateTime())).append("\",");
            json.append("\"customer_name\":\"").append(escapeJson(ct.customerName())).append("\",");
            json.append("\"customer_type\":\"").append(escapeJson(ct.customerType())).append("\",");
            json.append("\"phone\":\"").append(escapeJson(ct.phone())).append("\",");
            json.append("\"notes\":\"").append(escapeJson(ct.notes())).append("\"}");
        }
        json.append("],\"supplier_transactions\":[");
        first = true;
        for (java.util.Map<String, String> st : supplierTxs) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"time\":\"").append(escapeJson(st.get("time"))).append("\",");
            json.append("\"type\":\"").append(escapeJson(st.get("type"))).append("\",");
            json.append("\"product_name\":\"").append(escapeJson(st.get("product_name"))).append("\",");
            json.append("\"quantity\":").append(st.get("quantity")).append(",");
            json.append("\"price\":").append(st.get("price")).append(",");
            json.append("\"profit\":").append(st.get("profit")).append(",");
            json.append("\"notes\":\"").append(escapeJson(st.get("notes"))).append("\",");
            json.append("\"supplier_code\":\"").append(escapeJson(st.get("supplier_code"))).append("\",");
            json.append("\"supplier_name\":\"").append(escapeJson(st.get("supplier_name"))).append("\",");
            json.append("\"supplier_phone\":\"").append(escapeJson(st.get("supplier_phone"))).append("\"}");
        }
        json.append("]}");

        // Compute summary counts from transactions
        int saleCount = 0, purchaseCount = 0, returnCount = 0;
        for (DailyTransaction tx : txs) {
            if ("بيع".equals(tx.type())) saleCount++;
            else if ("شراء".equals(tx.type())) purchaseCount++;
            else if ("مرتجع".equals(tx.type())) returnCount++;
        }
        double netMovement = card.sales() - card.wholesale() + card.deposit() - card.withdrawal();

        // 5. Insert or replace the archive with structured fields
        try (PreparedStatement s = c.prepareStatement("INSERT OR REPLACE INTO DailyReportArchives(report_date,snapshot_data,sale_count,purchase_count,return_count,total_sales,total_purchases,total_profit,net_movement) VALUES(?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, d);
            s.setString(2, json.toString());
            s.setInt(3, saleCount);
            s.setInt(4, purchaseCount);
            s.setInt(5, returnCount);
            s.setDouble(6, card.sales());
            s.setDouble(7, card.wholesale());
            s.setDouble(8, card.profit());
            s.setDouble(9, netMovement);
            s.executeUpdate();
        }
    }

    /** Retrieve all daily report archives ordered by date descending. */
    public List<DailyReportArchive> allDailyReportArchives() {
        return allDailyReportArchives(-1, 0);
    }

    /** Retrieve daily report archives with pagination. Pass limit=-1 for all. */
    public List<DailyReportArchive> allDailyReportArchives(int limit, int offset) {
        List<DailyReportArchive> out = new ArrayList<>();
        String sqlHeader = "SELECT id,report_date,snapshot_data,coalesce(sale_count,0),coalesce(purchase_count,0),coalesce(return_count,0),coalesce(total_sales,0),coalesce(total_purchases,0),coalesce(total_profit,0),coalesce(net_movement,0) FROM DailyReportArchives ORDER BY report_date DESC";
        String sql = limit > 0 ? sqlHeader + " LIMIT ? OFFSET ?" : sqlHeader;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (limit > 0) { s.setInt(1, limit); s.setInt(2, offset); }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new DailyReportArchive(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getDouble(10)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public int countDailyReportArchives() {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM DailyReportArchives"); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) { throw fail(e); }
    }

    public int countMonthlyReportArchives() {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM MonthlyReportArchives"); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        } catch (SQLException e) { throw fail(e); }
    }

    /** Retrieve a specific archive by date. */
    public DailyReportArchive dailyReportArchiveByDate(String dateStr) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,report_date,snapshot_data,coalesce(sale_count,0),coalesce(purchase_count,0),coalesce(return_count,0),coalesce(total_sales,0),coalesce(total_purchases,0),coalesce(total_profit,0),coalesce(net_movement,0) FROM DailyReportArchives WHERE report_date=?")) {
            s.setString(1, dateStr);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new DailyReportArchive(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getDouble(7), r.getDouble(8), r.getDouble(9), r.getDouble(10));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Escape a string for JSON. */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Generate monthly report for the given month. Called at month-end.
     * Aggregates all daily cards for that month and saves to MonthlyReports table.
     */
    public void generateMonthlyReport(String yearMonth) {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                generateMonthlyReportInternal(c, LocalDate.parse(yearMonth + "-01"));
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    private void generateMonthlyReportInternal(Connection c, LocalDate monthEnd) throws SQLException {
        LocalDate monthStart = monthEnd.withDayOfMonth(1);
        String yearMonth = monthStart.format(DateTimeFormatter.ofPattern("yyyy-MM"));

        // Check if report already exists
        try (PreparedStatement check = c.prepareStatement("SELECT id FROM MonthlyReports WHERE year_month=?")) {
            check.setString(1, yearMonth);
            try (ResultSet r = check.executeQuery()) {
                if (r.next()) return; // Already generated
            }
        }

        // Aggregate all daily cards for this month
        String sql = "SELECT coalesce(sum(sales),0), coalesce(sum(profit),0), coalesce(sum(wholesale),0), "
            + "coalesce(sum(deposit),0), coalesce(sum(withdrawal),0), coalesce(sum(transaction_count),0), "
            + "count(*), coalesce(max(balance),0) FROM DailyCards WHERE card_date>=? AND card_date<=? AND is_closed=1";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    double totalSales = r.getDouble(1);
                    double totalProfit = r.getDouble(2);
                    double totalWholesale = r.getDouble(3);
                    double totalDeposit = r.getDouble(4);
                    double totalWithdrawal = r.getDouble(5);
                    int totalTransactions = r.getInt(6);
                    int daysCount = r.getInt(7);
                    double closingBalance = r.getDouble(8);

                    try (PreparedStatement ins = c.prepareStatement("INSERT OR REPLACE INTO MonthlyReports(year_month,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,days_count) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        ins.setString(1, yearMonth);
                        ins.setDouble(2, totalSales);
                        ins.setDouble(3, totalProfit);
                        ins.setDouble(4, totalWholesale);
                        ins.setDouble(5, totalDeposit);
                        ins.setDouble(6, totalWithdrawal);
                        ins.setInt(7, totalTransactions);
                        ins.setDouble(8, closingBalance);
                        ins.setInt(9, daysCount);
                        ins.executeUpdate();

                        // ── Save detailed monthly archive (all daily transactions merged) ──
                        saveMonthlyReportSnapshot(c, monthStart, monthEnd, yearMonth, daysCount);
                    }
                }
            }
        }
    }

    /** Save a detailed monthly report snapshot combining all daily data. */
    private void saveMonthlyReportSnapshot(Connection c, LocalDate monthStart, LocalDate monthEnd, String yearMonth, int daysCount) throws SQLException {
        // ── Duplicate check: skip if snapshot already exists for this month ──
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM MonthlyReportArchives WHERE year_month=?")) {
            s.setString(1, yearMonth);
            try (ResultSet r = s.executeQuery()) { if (r.next()) return; }
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"year_month\":\"").append(escapeJson(yearMonth)).append("\",");
        json.append("\"days_count\":").append(daysCount).append(",");

        // Get all daily cards for this month
        List<DailyCard> cards = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date>=? AND card_date<=? AND is_closed=1 ORDER BY card_date")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    cards.add(new DailyCard(r.getInt(1), r.getString(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getInt(9), r.getInt(10) == 1));
                }
            }
        }

        // Add cards array
        json.append("\"daily_cards\":[");
        boolean first = true;
        for (DailyCard card : cards) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"date\":\"").append(escapeJson(card.date())).append("\",");
            json.append("\"sales\":").append(card.sales()).append(",");
            json.append("\"profit\":").append(card.profit()).append(",");
            json.append("\"wholesale\":").append(card.wholesale()).append(",");
            json.append("\"balance\":").append(card.balance()).append(",");
            json.append("\"deposit\":").append(card.deposit()).append(",");
            json.append("\"withdrawal\":").append(card.withdrawal()).append(",");
            json.append("\"transaction_count\":").append(card.transactionCount()).append("}");
        }
        json.append("],\"transactions\":[");

        // Get all daily transactions for all cards this month
        first = true;
        for (DailyCard card : cards) {
            try (PreparedStatement s = c.prepareStatement("SELECT dt.id,dt.type,coalesce(dt.product_name,''),dt.quantity,dt.price,dt.profit,dt.time_stamp,coalesce(dt.notes,''),coalesce(dt.customer_code,''),coalesce(dt.customer_name,''),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(dt.measurement_unit,'') FROM DailyTransactions dt LEFT JOIN Transactions t ON t.id=dt.transaction_id LEFT JOIN Products p ON p.id=t.product_id WHERE dt.card_id=? ORDER BY dt.time_stamp")) {
                s.setInt(1, card.id());
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        if (!first) json.append(",");
                        first = false;
                        json.append("{\"date\":\"").append(escapeJson(card.date())).append("\",");
                        json.append("\"type\":\"").append(escapeJson(r.getString(2))).append("\",");
                        json.append("\"product_name\":\"").append(escapeJson(r.getString(3))).append("\",");
                        json.append("\"quantity\":").append(r.getDouble(4)).append(",");
                        json.append("\"price\":").append(r.getDouble(5)).append(",");
                        json.append("\"profit\":").append(r.getDouble(6)).append(",");
                        json.append("\"time\":\"").append(escapeJson(r.getString(7))).append("\",");
                        json.append("\"notes\":\"").append(escapeJson(r.getString(8))).append("\",");
                        json.append("\"customer_name\":\"").append(escapeJson(r.getString(10))).append("\",");
                        json.append("\"supplier_name\":\"").append(escapeJson(r.getString(12))).append("\"}");
                    }
                }
            }
        }
        json.append("],\"customer_transactions\":[");

        // Get all customer transactions for this month
        first = true;
        try (PreparedStatement s = c.prepareStatement("SELECT ct.id,ct.type,coalesce(ct.product_name,''),ct.quantity,ct.price,ct.total,ct.date_time,coalesce(ct.notes,''),coalesce(cu.name,''),coalesce(cu.customer_type,''),coalesce(cu.phone,'') FROM CustomerTransactions ct JOIN Customers cu ON cu.id=ct.customer_id WHERE date(ct.date_time)>=? AND date(ct.date_time)<=? ORDER BY ct.date_time")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!first) json.append(",");
                    first = false;
                    json.append("{\"type\":\"").append(escapeJson(r.getString(2))).append("\",");
                    json.append("\"product_name\":\"").append(escapeJson(r.getString(3))).append("\",");
                    json.append("\"quantity\":").append(r.getDouble(4)).append(",");
                    json.append("\"price\":").append(r.getDouble(5)).append(",");
                    json.append("\"total\":").append(r.getDouble(6)).append(",");
                    json.append("\"date_time\":\"").append(escapeJson(r.getString(7))).append("\",");
                    json.append("\"customer_name\":\"").append(escapeJson(r.getString(9))).append("\",");
                    json.append("\"customer_type\":\"").append(escapeJson(r.getString(10))).append("\",");
                    json.append("\"phone\":\"").append(escapeJson(r.getString(11))).append("\",");
                    json.append("\"notes\":\"").append(escapeJson(r.getString(8))).append("\"}");
                }
            }
        }
        // ── Compute analytics from the monthly data ──

        // Compute return count
        int returnCount = 0;
        try (PreparedStatement s = c.prepareStatement("SELECT count(*) FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date>=? AND dc.card_date<=? AND dt.type=?")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            s.setString(3, "مرتجع");
            try (ResultSet r = s.executeQuery()) { if (r.next()) returnCount = r.getInt(1); }
        }

        // Top selling products
        StringBuilder topProducts = new StringBuilder();
        topProducts.append("[\"top_products\":[");
        boolean tpFirst = true;
        try (PreparedStatement s = c.prepareStatement("SELECT product_name,sum(quantity) as qty,sum(dt.profit) as val FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date>=? AND dc.card_date<=? AND dt.type=? GROUP BY product_name ORDER BY qty DESC LIMIT 10")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            s.setString(3, "بيع");
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!tpFirst) topProducts.append(",");
                    tpFirst = false;
                    topProducts.append("{\"name\":\"").append(escapeJson(r.getString(1))).append("\",");
                    topProducts.append("\"quantity\":").append(r.getDouble(2)).append(",");
                    topProducts.append("\"value\":").append(r.getDouble(3)).append("}");
                }
            }
        }
        topProducts.append("]");

        // Top customers
        StringBuilder topCustomers = new StringBuilder();
        topCustomers.append(",\"top_customers\":[");
        boolean tcFirst = true;
        try (PreparedStatement s = c.prepareStatement("SELECT coalesce(customer_name,'') as cname,count(*) as cnt,sum(quantity * price) as total FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date>=? AND dc.card_date<=? AND dt.type=? AND dt.customer_name!='' GROUP BY cname ORDER BY total DESC LIMIT 10")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            s.setString(3, "بيع");
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!tcFirst) topCustomers.append(",");
                    tcFirst = false;
                    topCustomers.append("{\"name\":\"").append(escapeJson(r.getString(1))).append("\",");
                    topCustomers.append("\"count\":").append(r.getInt(2)).append(",");
                    topCustomers.append("\"total\":").append(r.getDouble(3)).append("}");
                }
            }
        }
        topCustomers.append("]");

        // Top suppliers
        StringBuilder topSuppliers = new StringBuilder();
        topSuppliers.append(",\"top_suppliers\":[");
        boolean tsFirst = true;
        try (PreparedStatement s = c.prepareStatement("SELECT coalesce(p.supplier_name,'') as sname,count(*) as cnt,sum(dt.quantity * dt.price) as total FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id LEFT JOIN Transactions t ON t.id=dt.transaction_id LEFT JOIN Products p ON p.id=t.product_id WHERE dc.card_date>=? AND dc.card_date<=? AND dt.type=? AND p.supplier_name IS NOT NULL AND p.supplier_name!='' GROUP BY sname ORDER BY total DESC LIMIT 10")) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            s.setString(3, "شراء");
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (!tsFirst) topSuppliers.append(",");
                    tsFirst = false;
                    topSuppliers.append("{\"name\":\"").append(escapeJson(r.getString(1))).append("\",");
                    topSuppliers.append("\"count\":").append(r.getInt(2)).append(",");
                    topSuppliers.append("\"total\":").append(r.getDouble(3)).append("}");
                }
            }
        }
        topSuppliers.append("]");

        json.append(",\"return_count\":").append(returnCount).append(",");
        // Remove outer brackets from topProducts and append directly
        json.append(topProducts.substring(1)); // "top_products":[...]
        json.append(topCustomers);
        json.append(topSuppliers);
        json.append("}");

        // Save to MonthlyReportArchives
        try (PreparedStatement s = c.prepareStatement("INSERT OR REPLACE INTO MonthlyReportArchives(year_month,snapshot_data,days_count) VALUES(?,?,?)")) {
            s.setString(1, yearMonth);
            s.setString(2, json.toString());
            s.setInt(3, daysCount);
            s.executeUpdate();
        }
    }

    public List<MonthlyReportArchive> allMonthlyReportArchives() {
        return allMonthlyReportArchives(-1, 0);
    }

    public List<MonthlyReportArchive> allMonthlyReportArchives(int limit, int offset) {
        List<MonthlyReportArchive> out = new ArrayList<>();
        String sql = limit > 0
            ? "SELECT id,year_month,snapshot_data,days_count FROM MonthlyReportArchives ORDER BY year_month DESC LIMIT ? OFFSET ?"
            : "SELECT id,year_month,snapshot_data,days_count FROM MonthlyReportArchives ORDER BY year_month DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (limit > 0) { s.setInt(1, limit); s.setInt(2, offset); }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new MonthlyReportArchive(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public MonthlyReportArchive monthlyReportArchiveByMonth(String yearMonth) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,year_month,snapshot_data,days_count FROM MonthlyReportArchives WHERE year_month=?")) {
            s.setString(1, yearMonth);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new MonthlyReportArchive(r.getInt(1), r.getString(2), r.getString(3), r.getInt(4));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /*** Generate yearly report for the given year. Called at year-end.
     * Aggregates all monthly reports for that year and saves to YearlyReports table.
     */
    public void generateYearlyReport(int year) {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                generateYearlyReportInternal(c, year);
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    private void generateYearlyReportInternal(Connection c, int year) throws SQLException {
        // Check if report already exists
        try (PreparedStatement check = c.prepareStatement("SELECT id FROM YearlyReports WHERE year=?")) {
            check.setInt(1, year);
            try (ResultSet r = check.executeQuery()) {
                if (r.next()) return; // Already generated
            }
        }

        // Aggregate all monthly reports for this year
        String sql = "SELECT coalesce(sum(total_sales),0), coalesce(sum(total_profit),0), coalesce(sum(total_wholesale),0), "
            + "coalesce(sum(total_deposit),0), coalesce(sum(total_withdrawal),0), coalesce(sum(total_transactions),0), "
            + "count(*), coalesce(max(closing_balance),0) FROM MonthlyReports WHERE year_month LIKE ?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    double totalSales = r.getDouble(1);
                    double totalProfit = r.getDouble(2);
                    double totalWholesale = r.getDouble(3);
                    double totalDeposit = r.getDouble(4);
                    double totalWithdrawal = r.getDouble(5);
                    int totalTransactions = r.getInt(6);
                    int monthsCount = r.getInt(7);
                    double closingBalance = r.getDouble(8);

                    try (PreparedStatement ins = c.prepareStatement("INSERT OR REPLACE INTO YearlyReports(year,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,months_count) VALUES(?,?,?,?,?,?,?,?,?)")) {
                        ins.setInt(1, year);
                        ins.setDouble(2, totalSales);
                        ins.setDouble(3, totalProfit);
                        ins.setDouble(4, totalWholesale);
                        ins.setDouble(5, totalDeposit);
                        ins.setDouble(6, totalWithdrawal);
                        ins.setInt(7, totalTransactions);
                        ins.setDouble(8, closingBalance);
                        ins.setInt(9, monthsCount);
                        ins.executeUpdate();
                    }                }
            }
        }
    }

    /*** Archive (close + generate report) all unclosed months before the current month.
     * Called on startup to ensure no data is lost.
     */
    /**
     * Compare current date with the latest closed DailyCard.
     * Auto-close any missed days and generate required monthly/yearly reports
     * by walking through each day sequentially.
     * Called once at application startup instead of relying on a midnight Timer.
     */
    public void checkAndGenerateReports() {
        LocalDate today = LocalDate.now();
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Find the latest closed card date
                String lastClosedDate = null;
                try (PreparedStatement s = c.prepareStatement("SELECT max(card_date) FROM DailyCards WHERE is_closed=1")) {
                    try (ResultSet r = s.executeQuery()) { if (r.next()) lastClosedDate = r.getString(1); }
                }

            LocalDate walkDate;
            if (lastClosedDate != null) {
                // Normal case: walk from the day after the last closed card
                walkDate = LocalDate.parse(lastClosedDate).plusDays(1);
            } else {
                // No closed cards — try using the earliest card date as starting point.
                // This handles the case where the user closed the app on Day 1
                // and reopened on Day 2+ without ever having a closed card.
                String firstCardDate = null;
                try (PreparedStatement s = c.prepareStatement("SELECT min(card_date) FROM DailyCards")) {
                    try (ResultSet r = s.executeQuery()) { if (r.next()) firstCardDate = r.getString(1); }
                }
                if (firstCardDate == null) {
                    // No cards at all — fresh install or reset
                    c.commit();
                    ensureTodayCard();
                    return;
                }
                walkDate = LocalDate.parse(firstCardDate);
            }
                while (walkDate.isBefore(today)) {
                    String walkStr = walkDate.format(DATE_FMT);
                    String nextStr = walkDate.plusDays(1).format(DATE_FMT);

                    // Ensure this day's card exists (create with zero values and current balance)
                    double curBalance = currentBalance(c);
                    try (PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO DailyCards(card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed) VALUES(?,0,0,0,?,0,0,0,0)")) {
                        s.setString(1, walkStr);
                        s.setDouble(2, curBalance);
                        s.executeUpdate();
                    }

                    // Calculate net movement for this day
                    double daySales = 0, dayWholesale = 0, dayDeposit = 0, dayWithdrawal = 0;
                    try (PreparedStatement s = c.prepareStatement("SELECT coalesce(sales,0),coalesce(wholesale,0),coalesce(deposit,0),coalesce(withdrawal,0) FROM DailyCards WHERE card_date=?")) {
                        s.setString(1, walkStr);
                        try (ResultSet r = s.executeQuery()) { if (r.next()) { daySales = r.getDouble(1); dayWholesale = r.getDouble(2); dayDeposit = r.getDouble(3); dayWithdrawal = r.getDouble(4); } }
                    }
                    double dayNet = daySales - dayWholesale + dayDeposit - dayWithdrawal;

                    // Record the daily rollover in CashLedger
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO CashLedger(type,amount,date_time,notes) VALUES('DAILY_ROLLOVER',?," + NOW + ",'ترحيل رصيد اليوم (الخزينة)')")) {
                        s.setDouble(1, dayNet);
                        s.executeUpdate();
                    }

                    // Close this day's card
                    double actualBalance = currentBalance(c);
                    try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET is_closed=1, balance=? WHERE card_date=?")) {
                        s.setDouble(1, actualBalance);
                        s.setString(2, walkStr);
                        s.executeUpdate();
                    }

                    // Create next day's card with the current balance
                    try (PreparedStatement s = c.prepareStatement("INSERT OR IGNORE INTO DailyCards(card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed) VALUES(?,0,0,0,?,0,0,0,0)")) {
                        s.setString(1, nextStr);
                        s.setDouble(2, actualBalance);
                        s.executeUpdate();
                    }

                    // Save daily report snapshot for this day so it appears in the reports page
                    saveDailyReportSnapshot(c, walkDate);

                    walkDate = walkDate.plusDays(1);
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }

        // Always ensure today's card exists
        ensureTodayCard();
        // Generate monthly/yearly reports for any completed months
        archiveOldMonths();
    }

    public void archiveOldMonths() {
        LocalDate today = LocalDate.now();
        LocalDate currentMonthStart = today.withDayOfMonth(1);

        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Find all distinct months in DailyCards before current month
                try (PreparedStatement s = c.prepareStatement("SELECT DISTINCT substr(card_date,1,7) as ym FROM DailyCards WHERE card_date<? AND is_closed=1 ORDER BY ym")) {
                    s.setString(1, currentMonthStart.format(DATE_FMT));
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            String ym = r.getString(1);
                            LocalDate monthEnd = LocalDate.parse(ym + "-01").withDayOfMonth(LocalDate.parse(ym + "-01").lengthOfMonth());
                            generateMonthlyReportInternal(c, monthEnd);
                            // Remove daily report archives for this month since they're merged into the monthly report
                            String monthStartStr = ym + "-01";
                            String monthEndStr = monthEnd.format(DATE_FMT);
                            try (PreparedStatement del = c.prepareStatement("DELETE FROM DailyReportArchives WHERE report_date BETWEEN ? AND ?")) {
                                del.setString(1, monthStartStr);
                                del.setString(2, monthEndStr);
                                del.executeUpdate();
                            }
                        }
                    }
                }

                // Find all distinct years before current year and generate yearly reports
                try (PreparedStatement s = c.prepareStatement("SELECT DISTINCT substr(year_month,1,4) as yr FROM MonthlyReports WHERE year_month<? ORDER BY yr")) {
                    s.setString(1, today.format(DateTimeFormatter.ofPattern("yyyy-MM")));
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            generateYearlyReportInternal(c, Integer.parseInt(r.getString(1)));
                        }
                    }
                }

                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    // ========== Monthly & Yearly Report Retrieval ==========

    public List<MonthlyReport> allMonthlyReports() {
        List<MonthlyReport> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,days_count FROM MonthlyReports ORDER BY year_month DESC")) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new MonthlyReport(r.getString(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getInt(7), r.getDouble(8), r.getInt(9)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public MonthlyReport getMonthlyReport(String yearMonth) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,days_count FROM MonthlyReports WHERE year_month=?")) {
            s.setString(1, yearMonth);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new MonthlyReport(r.getString(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getInt(7), r.getDouble(8), r.getInt(9));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    public List<YearlyReport> allYearlyReports() {
        List<YearlyReport> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,months_count FROM YearlyReports ORDER BY year DESC")) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new YearlyReport(r.getInt(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getInt(7), r.getDouble(8), r.getInt(9)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public YearlyReport getYearlyReport(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year,total_sales,total_profit,total_wholesale,total_deposit,total_withdrawal,total_transactions,closing_balance,months_count FROM YearlyReports WHERE year=?")) {
            s.setInt(1, year);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new YearlyReport(r.getInt(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getInt(7), r.getDouble(8), r.getInt(9));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get total return count for a given year. */
    public int yearlyReturnCount(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date LIKE ? AND dt.type=?")) {
            s.setString(1, year + "%");
            s.setString(2, "مرتجع");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getInt(1); }
        } catch (SQLException e) { throw fail(e); }
        return 0;
    }

    public record YearlyTopProduct(String name, double quantity, double value) {}
    /** Get top selling products for a year. */
    public List<YearlyTopProduct> yearlyTopProducts(int year) {
        List<YearlyTopProduct> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT dt.product_name,sum(dt.quantity) as qty,sum(dt.quantity * dt.price) as val FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date LIKE ? AND dt.type=? AND dt.product_name!='' GROUP BY dt.product_name ORDER BY qty DESC LIMIT 10")) {
            s.setString(1, year + "%");
            s.setString(2, "بيع");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new YearlyTopProduct(r.getString(1), r.getDouble(2), r.getDouble(3))); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public record YearlyTopPerson(String name, int count, double total) {}
    /** Get top customers for a year. */
    public List<YearlyTopPerson> yearlyTopCustomers(int year) {
        List<YearlyTopPerson> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT coalesce(customer_name,'') as cname,count(*) as cnt,sum(quantity * price) as total FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id WHERE dc.card_date LIKE ? AND dt.type=? AND dt.customer_name!='' GROUP BY cname ORDER BY total DESC LIMIT 10")) {
            s.setString(1, year + "%");
            s.setString(2, "بيع");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new YearlyTopPerson(r.getString(1), r.getInt(2), r.getDouble(3))); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get top suppliers for a year. */
    public List<YearlyTopPerson> yearlyTopSuppliers(int year) {
        List<YearlyTopPerson> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT coalesce(p.supplier_name,'') as sname,count(*) as cnt,sum(dt.quantity * dt.price) as total FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id LEFT JOIN Transactions t ON t.id=dt.transaction_id LEFT JOIN Products p ON p.id=t.product_id WHERE dc.card_date LIKE ? AND dt.type=? AND p.supplier_name IS NOT NULL AND p.supplier_name!='' GROUP BY sname ORDER BY total DESC LIMIT 10")) {
            s.setString(1, year + "%");
            s.setString(2, "شراء");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new YearlyTopPerson(r.getString(1), r.getInt(2), r.getDouble(3))); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get the best month for sales in a year. Returns yearMonth string or null. */
    public String bestMonthBySales(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month FROM MonthlyReports WHERE year_month LIKE ? ORDER BY total_sales DESC LIMIT 1")) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get the best month for profit in a year. Returns yearMonth string or null. */
    public String bestMonthByProfit(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month FROM MonthlyReports WHERE year_month LIKE ? ORDER BY total_profit DESC LIMIT 1")) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }
    /** Get worst month (lowest sales) in a year. */
    public String worstMonthBySales(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month FROM MonthlyReports WHERE year_month LIKE ? AND total_sales>0 ORDER BY total_sales ASC LIMIT 1")) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get worst month (lowest profit) in a year. */
    public String worstMonthByProfit(int year) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT year_month FROM MonthlyReports WHERE year_month LIKE ? AND total_profit>0 ORDER BY total_profit ASC LIMIT 1")) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return r.getString(1); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get daily transaction totals grouped by type for a whole year. */
    public List<MonthlyCategoryRow> yearlyCategoryTotals(int year) {
        List<MonthlyCategoryRow> out = new ArrayList<>();
        String sql = "SELECT coalesce(type,''),count(*),coalesce(sum(CASE WHEN quantity > 0 THEN price * quantity ELSE price END),0),coalesce(sum(dt.profit),0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id"
            + " WHERE dc.card_date LIKE ? GROUP BY type ORDER BY sum(CASE WHEN quantity > 0 THEN price * quantity ELSE price END) DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new MonthlyCategoryRow(r.getString(1), r.getInt(2), r.getDouble(3), r.getDouble(4))); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get best day (highest sales) in a month. */
    public DailyCard bestDayInMonth(String yearMonth) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date LIKE ? AND is_closed=1 ORDER BY sales DESC LIMIT 1")) {
            s.setString(1, yearMonth + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return cardFromResultSet(r); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get worst day (lowest sales, excluding zero) in a month. */
    public DailyCard worstDayInMonth(String yearMonth) {
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date LIKE ? AND is_closed=1 AND sales>0 ORDER BY sales ASC LIMIT 1")) {
            s.setString(1, yearMonth + "%");
            try (ResultSet r = s.executeQuery()) { if (r.next()) return cardFromResultSet(r); }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get daily transaction totals grouped by type for a month. */
    public record MonthlyCategoryRow(String type, int count, double total, double profit) {}

    public List<MonthlyCategoryRow> monthlyCategoryTotals(String yearMonth) {
        List<MonthlyCategoryRow> out = new ArrayList<>();
        // Use CASE: for product transactions (quantity>0) use price*quantity; for cash transactions (quantity=0) use price directly
        String sql = "SELECT coalesce(type,''),count(*),coalesce(sum(CASE WHEN quantity > 0 THEN price * quantity ELSE price END),0),coalesce(sum(dt.profit),0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id"
            + " WHERE dc.card_date LIKE ? GROUP BY type ORDER BY sum(CASE WHEN quantity > 0 THEN price * quantity ELSE price END) DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, yearMonth + "%");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(new MonthlyCategoryRow(r.getString(1), r.getInt(2), r.getDouble(3), r.getDouble(4))); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get all daily cards for a given month (year-month format: yyyy-MM). */
    public List<DailyCard> getMonthDailyCards(String yearMonth) {
        List<DailyCard> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date LIKE ? ORDER BY card_date")) {
            s.setString(1, yearMonth + "%");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(cardFromResultSet(r)); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get all daily cards for a given year. */
    public List<DailyCard> getYearDailyCards(int year) {
        List<DailyCard> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date LIKE ? ORDER BY card_date")) {
            s.setString(1, year + "%");
            try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(cardFromResultSet(r)); }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Total cumulative profit across all days (never resets). */
    public double totalProfit() {
        try (Connection c = DatabaseManager.getConnection()) {
            return scalar(c, "SELECT coalesce(sum(profit),0) FROM DailyCards", null);
        } catch (SQLException e) { throw fail(e); }
    }

    public TreasurySummary treasurySummary() {
        DailyCard today = ensureTodayCard();
        double todayBalance = today.sales() - today.wholesale() + today.deposit() - today.withdrawal();
        String monthStart = LocalDate.now().withDayOfMonth(1).format(DATE_FMT);
        String todayStr = LocalDate.now().format(DATE_FMT);
        try (Connection c = DatabaseManager.getConnection()) {
            double monthBalance = scalar(c, "SELECT coalesce(sum(sales - wholesale + deposit - withdrawal),0) FROM DailyCards WHERE card_date>=? AND card_date<? AND is_closed=1", null, monthStart, todayStr);
            return new TreasurySummary(todayBalance, today.sales(), today.profit(), today.wholesale(), monthBalance);
        } catch (SQLException e) { throw fail(e); }
    }

    /** Read-only card summary for a specific date, used by the PDF reports. */
    public DailyCardRow getDailyCard(LocalDate date) {
        String d = date.format(DATE_FMT);
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT sales,profit,wholesale,deposit,withdrawal,transaction_count FROM DailyCards WHERE card_date=?")) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    double sales = r.getDouble(1), profit = r.getDouble(2), wholesale = r.getDouble(3), deposit = r.getDouble(4), withdrawal = r.getDouble(5);
                    double balance = sales - wholesale + deposit - withdrawal;
                    return new DailyCardRow(d, sales, profit, wholesale, balance, deposit, withdrawal, r.getInt(6));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return new DailyCardRow(d, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Read-only transaction list for a specific date, used by the PDF reports. */
    public List<TransactionRow> getTransactionsForDate(LocalDate date) {
        String d = date.format(DATE_FMT);
        List<TransactionRow> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,dt.product_name,dt.quantity,dt.price,dt.profit,coalesce(dt.notes,''),"
            + "coalesce(p.unit_type,'COUNT'),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),"
            + "coalesce(dt.measurement_unit,''),"
            + "coalesce(dt.customer_code,''),coalesce(dt.customer_name,''),coalesce(p.package_weight,0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "WHERE dc.card_date=? ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(8);
                    double pkgW = r.getDouble(14);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new TransactionRow(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), pkgW));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get all transactions for an entire month, aggregated from all days.
     * Used by monthly report PDF export.
     */
    public List<TransactionRow> getTransactionsForMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<TransactionRow> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,dt.product_name,dt.quantity,dt.price,dt.profit,coalesce(dt.notes,''),"
            + "coalesce(p.unit_type,'COUNT'),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),"
            + "coalesce(dt.measurement_unit,''),"
            + "coalesce(dt.customer_code,''),coalesce(dt.customer_name,'')"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "WHERE dc.card_date>=? AND dc.card_date<=? ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, monthStart.format(DATE_FMT));
            s.setString(2, monthEnd.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(8);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new TransactionRow(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getDouble(14)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get aggregated daily card for an entire month (sum of all daily cards).
     * Used by monthly report PDF export.
     */
    public DailyCardRow getDailyCardForMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        String monthStartStr = monthStart.format(DATE_FMT);
        String monthEndStr = monthEnd.format(DATE_FMT);
        String d = monthStartStr + " - " + monthEndStr;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT coalesce(sum(sales),0),coalesce(sum(profit),0),coalesce(sum(wholesale),0),"
            + "coalesce(sum(deposit),0),coalesce(sum(withdrawal),0),coalesce(sum(transaction_count),0) "
            + "FROM DailyCards WHERE card_date>=? AND card_date<=?")) {
            s.setString(1, monthStartStr);
            s.setString(2, monthEndStr);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    double sales = r.getDouble(1), profit = r.getDouble(2), wholesale = r.getDouble(3), deposit = r.getDouble(4), withdrawal = r.getDouble(5);
                    double balance = sales - wholesale + deposit - withdrawal;
                    return new DailyCardRow(d, sales, profit, wholesale, balance, deposit, withdrawal, r.getInt(6));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return new DailyCardRow(d, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Get all transactions for an entire year, aggregated from all months.
     * Used by yearly report PDF export.
     */
    public List<TransactionRow> getTransactionsForYear(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        List<TransactionRow> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,dt.product_name,dt.quantity,dt.price,dt.profit,coalesce(dt.notes,''),"
            + "coalesce(p.unit_type,'COUNT'),coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),"
            + "coalesce(dt.measurement_unit,''),"
            + "coalesce(dt.customer_code,''),coalesce(dt.customer_name,'')"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "WHERE dc.card_date>=? AND dc.card_date<=? ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, yearStart.format(DATE_FMT));
            s.setString(2, yearEnd.format(DATE_FMT));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(8);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new TransactionRow(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11), r.getString(12), r.getString(13), r.getDouble(14)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get aggregated daily card for an entire year.
     * Used by yearly report PDF export.
     */
    public DailyCardRow getDailyCardForYear(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        String startStr = yearStart.format(DATE_FMT);
        String endStr = yearEnd.format(DATE_FMT);
        String d = startStr + " - " + endStr;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT coalesce(sum(sales),0),coalesce(sum(profit),0),coalesce(sum(wholesale),0),"
            + "coalesce(sum(deposit),0),coalesce(sum(withdrawal),0),coalesce(sum(transaction_count),0) "
            + "FROM DailyCards WHERE card_date>=? AND card_date<=?")) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    double sales = r.getDouble(1), profit = r.getDouble(2), wholesale = r.getDouble(3), deposit = r.getDouble(4), withdrawal = r.getDouble(5);
                    double balance = sales - wholesale + deposit - withdrawal;
                    return new DailyCardRow(d, sales, profit, wholesale, balance, deposit, withdrawal, r.getInt(6));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return new DailyCardRow(d, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Get cash ledger entries for a month range.
     */
    public List<CashLedgerRow> getCashLedgerForMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        String startStr = monthStart.format(DATE_FMT);
        String endStr = monthEnd.format(DATE_FMT);
        List<CashLedgerRow> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT type, amount, date_time, coalesce(notes,''), coalesce(person_name,'')"
            + " FROM CashLedger WHERE date(date_time)>=? AND date(date_time)<=? AND type IN ('DEPOSIT','WITHDRAWAL') ORDER BY date_time")) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String dt = r.getString(3);
                    String time = dt != null && dt.length() > 10 ? dt.substring(11, 16) : "";
                    out.add(new CashLedgerRow(r.getString(1), r.getDouble(2), time, r.getString(4), r.getString(5)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get cash ledger entries for a year range.
     */
    public List<CashLedgerRow> getCashLedgerForYear(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        String startStr = yearStart.format(DATE_FMT);
        String endStr = yearEnd.format(DATE_FMT);
        List<CashLedgerRow> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT type, amount, date_time, coalesce(notes,''), coalesce(person_name,'')"
            + " FROM CashLedger WHERE date(date_time)>=? AND date(date_time)<=? AND type IN ('DEPOSIT','WITHDRAWAL') ORDER BY date_time")) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String dt = r.getString(3);
                    String time = dt != null && dt.length() > 10 ? dt.substring(11, 16) : "";
                    out.add(new CashLedgerRow(r.getString(1), r.getDouble(2), time, r.getString(4), r.getString(5)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get customer transactions for a month range.
     */
    public List<CustomerTransaction> getCustomerTransactionsForMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        String startStr = monthStart.format(DATE_FMT);
        String endStr = monthEnd.format(DATE_FMT);
        List<CustomerTransaction> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT ct.id,ct.type,ct.product_name,ct.quantity,ct.price,ct.total,ct.date_time,coalesce(ct.notes,''),coalesce(cu.name,''),coalesce(cu.customer_type,''),coalesce(cu.phone,'')"
            + " FROM CustomerTransactions ct JOIN Customers cu ON cu.id=ct.customer_id "
            + "WHERE date(ct.date_time)>=? AND date(ct.date_time)<=? ORDER BY ct.date_time")) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new CustomerTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get customer transactions for a year range.
     */
    public List<CustomerTransaction> getCustomerTransactionsForYear(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        String startStr = yearStart.format(DATE_FMT);
        String endStr = yearEnd.format(DATE_FMT);
        List<CustomerTransaction> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT ct.id,ct.type,ct.product_name,ct.quantity,ct.price,ct.total,ct.date_time,coalesce(ct.notes,''),coalesce(cu.name,''),coalesce(cu.customer_type,''),coalesce(cu.phone,'')"
            + " FROM CustomerTransactions ct JOIN Customers cu ON cu.id=ct.customer_id "
            + "WHERE date(ct.date_time)>=? AND date(ct.date_time)<=? ORDER BY ct.date_time")) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new CustomerTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getString(11)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get supplier transactions for a month range.
     */
    public List<SupplierDailyRecord> getSupplierTransactionsForMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        String startStr = monthStart.format(DATE_FMT);
        String endStr = monthEnd.format(DATE_FMT);
        List<SupplierDailyRecord> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,coalesce(dt.product_name,''),dt.quantity,dt.price,"
            + "coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(sup.phone,''),coalesce(dt.notes,''),coalesce(p.unit_type,'COUNT'),coalesce(p.package_weight,0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "LEFT JOIN Suppliers sup ON sup.code=p.supplier_code "
            + "WHERE dc.card_date>=? AND dc.card_date<=? AND coalesce(p.supplier_code,'')!='' AND dt.type!='بيع'"
            + " ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(10);
                    double packageWeight = r.getDouble(11);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new SupplierDailyRecord(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getString(6), r.getString(7), r.getString(8), r.getString(9), unitType, packageWeight));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Get supplier transactions for a year range.
     */
    public List<SupplierDailyRecord> getSupplierTransactionsForYear(int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        String startStr = yearStart.format(DATE_FMT);
        String endStr = yearEnd.format(DATE_FMT);
        List<SupplierDailyRecord> out = new ArrayList<>();
        String sql = "SELECT dt.time_stamp,dt.type,coalesce(dt.product_name,''),dt.quantity,dt.price,"
            + "coalesce(p.supplier_code,''),coalesce(p.supplier_name,''),coalesce(sup.phone,''),coalesce(dt.notes,''),coalesce(p.unit_type,'COUNT'),coalesce(p.package_weight,0)"
            + " FROM DailyTransactions dt JOIN DailyCards dc ON dc.id=dt.card_id "
            + "LEFT JOIN Transactions t ON t.id=dt.transaction_id "
            + "LEFT JOIN Products p ON p.id=t.product_id "
            + "LEFT JOIN Suppliers sup ON sup.code=p.supplier_code "
            + "WHERE dc.card_date>=? AND dc.card_date<=? AND coalesce(p.supplier_code,'')!='' AND dt.type!='بيع'"
            + " ORDER BY dt.time_stamp";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, startStr);
            s.setString(2, endStr);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(10);
                    double packageWeight = r.getDouble(11);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new SupplierDailyRecord(r.getString(1), r.getString(2), r.getString(3), qty, price, total, r.getString(6), r.getString(7), r.getString(8), r.getString(9), unitType, packageWeight));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Read-only cash ledger entries (deposits & withdrawals) for a specific date. */
    public List<CashLedgerRow> getAllCashLedger() {
        List<CashLedgerRow> out = new ArrayList<>();
        String sql = "SELECT type, amount, date_time, coalesce(notes,''), coalesce(person_name,'') FROM CashLedger WHERE type IN ('DEPOSIT','WITHDRAWAL') ORDER BY date_time DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String dt = r.getString(3);
                    String time = dt != null && dt.length() > 10 ? dt.substring(11, 16) : "";
                    out.add(new CashLedgerRow(r.getString(1), r.getDouble(2), time, r.getString(4), r.getString(5)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<CashLedgerRow> getCashLedgerForDate(LocalDate date) {
        String d = date.format(DATE_FMT);
        List<CashLedgerRow> out = new ArrayList<>();
        String sql = "SELECT type, amount, date_time, coalesce(notes,''), coalesce(person_name,'') FROM CashLedger WHERE date(date_time)=? AND type IN ('DEPOSIT','WITHDRAWAL') ORDER BY date_time";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, d);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String dt = r.getString(3);
                    String time = dt != null && dt.length() > 10 ? dt.substring(11, 16) : "";
                    out.add(new CashLedgerRow(r.getString(1), r.getDouble(2), time, r.getString(4), r.getString(5)));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    // ========== Internal ==========

    private double currentBalance(Connection c) throws SQLException {
        return scalar(c, "SELECT coalesce(sum(CASE WHEN type IN ('SALE_INCOME','TAX_COLLECTED','DEPOSIT') THEN amount ELSE 0 END),0) - coalesce(sum(CASE WHEN type IN ('PURCHASE_EXPENSE','SALE_RETURN','WITHDRAWAL') THEN amount ELSE 0 END),0) FROM CashLedger", null);
    }

    private void validateForReturn(Connection c, int id, double q) throws SQLException {
        if (id <= 0 || q <= 0) throw new ValidationException("أدخل كود منتج وكمية صحيحة");
        try (PreparedStatement s = c.prepareStatement("SELECT unit_type FROM Products WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new ValidationException("المنتج غير موجود");
                String ut = r.getString(1);
                if ((UnitType.COUNT.name().equals(ut) || UnitType.PACKAGE.name().equals(ut)) && q != Math.rint(q)) throw new ValidationException("الكمية يجب أن تكون رقمًا صحيحًا");
            }
        }
    }

    private void recordDailyTransaction(Connection c, long transactionId, String type, String productName, double quantity, double price, double profit, String notes) throws SQLException {
        recordDailyTransaction(c, transactionId, type, productName, quantity, price, profit, notes, "", "", "");
    }

    private void recordDailyTransaction(Connection c, long transactionId, String type, String productName, double quantity, double price, double profit, String notes, String customerCode, String customerName, String measurementUnit) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        try (PreparedStatement s = c.prepareStatement("INSERT INTO DailyTransactions(card_id,transaction_id,type,product_name,quantity,price,profit,time_stamp,notes,customer_code,customer_name,measurement_unit) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setInt(1, card.id());
            s.setLong(2, transactionId);
            s.setString(3, type);
            s.setString(4, productName);
            s.setDouble(5, quantity);
            s.setDouble(6, price);
            s.setDouble(7, profit);
            s.setString(8, now);
            s.setString(9, notes);
            s.setString(10, customerCode != null ? customerCode : "");
            s.setString(11, customerName != null ? customerName : "");
            s.setString(12, measurementUnit != null ? measurementUnit : "");
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET transaction_count=transaction_count+1 WHERE id=?")) {
            s.setInt(1, card.id());
            s.executeUpdate();
        }
    }

    private DailyCard ensureTodayCardInternal(Connection c) throws SQLException {
        String today = LocalDate.now().format(DATE_FMT);
        try (PreparedStatement s = c.prepareStatement("SELECT id,card_date,sales,profit,wholesale,balance,deposit,withdrawal,transaction_count,is_closed FROM DailyCards WHERE card_date=?")) {
            s.setString(1, today);
            try (ResultSet r = s.executeQuery()) { if (r.next()) return cardFromResultSet(r); }
        }
        // New day — carry forward the actual cash balance from CashLedger (not zero!)
        double actualBalance = currentBalance(c);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO DailyCards(card_date,balance) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, today);
            s.setDouble(2, actualBalance);
            s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) { r.next(); return new DailyCard(r.getInt(1), today, 0, 0, 0, actualBalance, 0, 0, 0, false); }
        }
    }

    private void updateDailyCardSale(Connection c, double saleAmount, double profit) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET sales=sales+?, profit=profit+? WHERE id=?")) {
            s.setDouble(1, saleAmount);
            s.setDouble(2, profit);
            s.setInt(3, card.id());
            s.executeUpdate();
        }
    }

    private void updateDailyCardPurchase(Connection c, double cost) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET wholesale=wholesale+? WHERE id=?")) {
            s.setDouble(1, cost);
            s.setInt(2, card.id());
            s.executeUpdate();
        }
    }

    private void updateDailyCardReturn(Connection c, double saleReduction, double profitReduction) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET sales=sales-?, profit=profit-? WHERE id=?")) {
            s.setDouble(1, saleReduction);
            s.setDouble(2, profitReduction);
            s.setInt(3, card.id());
            s.executeUpdate();
        }
    }

    private void updateDailyCardWholesaleRefund(Connection c, double amount) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET wholesale = MAX(0, wholesale - ?), transaction_count = transaction_count + 1 WHERE card_date=?")) {
            s.setDouble(1, amount);
            s.setString(2, LocalDate.now().format(DATE_FMT));
            s.executeUpdate();
        }
    }

    private void updateDailyCardDeposit(Connection c, double amount) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET deposit=deposit+? WHERE id=?")) {
            s.setDouble(1, amount);
            s.setInt(2, card.id());
            s.executeUpdate();
        }
    }

    private void updateDailyCardWithdrawal(Connection c, double amount) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET withdrawal=withdrawal+? WHERE id=?")) {
            s.setDouble(1, amount);
            s.setInt(2, card.id());
            s.executeUpdate();
        }
    }

    /**
     * Withdrawal with deduction logic:
     * 1. Deduct from profit first.
     * 2. If profit is insufficient, remaining comes from wholesale (capital).
     * 3. Total amount is always deducted from balance via withdrawal.
     */
    /**
     * Withdrawal with deduction logic:
     * 1. Deduct from profit first.
     * 2. If profit is insufficient, remaining comes from wholesale (capital).
     * 3. Total amount is always deducted from balance via withdrawal.
     * @return true if profit was insufficient and wholesale was deducted
     */
    private boolean updateDailyCardWithdrawalDeduction(Connection c, double amount) throws SQLException {
        DailyCard card = ensureTodayCardInternal(c);
        double currentProfit = card.profit();
        if (currentProfit >= amount - PRICE_EPSILON) {
            // Profit covers the full withdrawal
            try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET profit=profit-?, withdrawal=withdrawal+?, transaction_count=transaction_count+1 WHERE id=?")) {
                s.setDouble(1, amount);
                s.setDouble(2, amount);
                s.setInt(3, card.id());
                s.executeUpdate();
            }
            return false;
        } else {
            // Profit is insufficient - deduct from wholesale (capital) too
            double remaining = amount - currentProfit;
            try (PreparedStatement s = c.prepareStatement("UPDATE DailyCards SET profit=0, wholesale=MAX(0, wholesale-?), withdrawal=withdrawal+?, transaction_count=transaction_count+1 WHERE id=?")) {
                s.setDouble(1, remaining);
                s.setDouble(2, amount);
                s.setInt(3, card.id());
                s.executeUpdate();
            }
            return true;
        }
    }

    // ========== Stock Integrity & Reconciliation ==========

    /**
     * Verifies that Stock.total_quantity = SUM(Batches.quantity_remaining) for ALL products.
     * Returns a list of product IDs where a mismatch was found, with expected vs actual quantities.
     */
    public record StockMismatch(int productId, String productName, double stockQuantity, double batchesQuantity) {}

    /** Checks every active product AND any inactive product with remaining batches, reports mismatches between Stock and Batches. */
    public List<StockMismatch> verifyStockIntegrity() {
        List<StockMismatch> mismatches = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement(
                "SELECT p.id, p.name, coalesce(st.total_quantity,0), coalesce((SELECT sum(b.quantity_remaining) FROM Batches b WHERE b.product_id=p.id),0) "
                + "FROM Products p LEFT JOIN Stock st ON st.product_id=p.id "
                + "WHERE p.is_active=1 OR (SELECT sum(b2.quantity_remaining) FROM Batches b2 WHERE b2.product_id=p.id) > 0")) {
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        int id = r.getInt(1);
                        String name = r.getString(2);
                        double stockQty = r.getDouble(3);
                        double batchesQty = r.getDouble(4);
                        if (Math.abs(stockQty - batchesQty) > PRICE_EPSILON) {
                            mismatches.add(new StockMismatch(id, name, stockQty, batchesQty));
                        }
                    }
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return mismatches;
    }

    /** Recalculates Stock.total_quantity from SUM(Batches.quantity_remaining) for a single product. */
    public void restockFromBatches(int productId) {
        if (productId <= 0) throw new ValidationException("اختر منتجًا أولاً");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                double batchesQty = scalar(c, "SELECT coalesce(sum(quantity_remaining),0) FROM Batches WHERE product_id=?", productId);
                // Update Stock to match the batches total (INSERT OR REPLACE to handle missing stock rows)
                try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO Stock(product_id,total_quantity) VALUES(?,?) ON CONFLICT(product_id) DO UPDATE SET total_quantity=excluded.total_quantity")) {
                    s.setInt(1, productId);
                    s.setDouble(2, batchesQty);
                    s.executeUpdate();
                }
                // Re-activate the product if it has stock and was deactivated
                if (batchesQty > PRICE_EPSILON) {
                    try (PreparedStatement u = c.prepareStatement("UPDATE Products SET is_active=1 WHERE id=? AND is_active=0")) {
                        u.setInt(1, productId);
                        u.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Recalculates Stock.total_quantity from SUM(Batches.quantity_remaining) for ALL products. */
    public void restockAllFromBatches() {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement(
                    "INSERT INTO Stock(product_id,total_quantity) "
                    + "SELECT p.id, coalesce((SELECT sum(b.quantity_remaining) FROM Batches b WHERE b.product_id=p.id),0) "
                    + "FROM Products p WHERE p.is_active=1 "
                    + "ON CONFLICT(product_id) DO UPDATE SET total_quantity=excluded.total_quantity")) {
                    s.executeUpdate();
                }
                // Re-activate products with stock that were deactivated
                try (PreparedStatement u = c.prepareStatement(
                    "UPDATE Products SET is_active=1 WHERE is_active=0 AND id IN (SELECT product_id FROM Batches WHERE quantity_remaining>0)")) {
                    u.executeUpdate();
                }
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /**
     * Deletes a transaction and reverses ALL its effects atomically:
     * stock, batches, cash ledger, daily card, and daily transaction records.
     * <p>
     * For OUT (sale) transactions, a new compensating batch is added at the
     * original cost (back-calculated from stored profit) to preserve cost basis.
     * For IN (purchase) transactions, the corresponding batch entries are removed.
     * For RETURN transactions, the returned batch entry is removed.
     * For cash transactions (DEPOSIT/WITHDRAWAL), only ledger/card effects are reversed.
     */
    public void deleteTransaction(long transactionId) {
        if (transactionId <= 0) throw new ValidationException("رقم العملية غير صالح");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Read transaction details (including returned_quantity for OUT transactions)
                String type; int productId; double qty, price, profit, returnedQty = 0, taxAmount = 0;
                try (PreparedStatement s = c.prepareStatement(
                    "SELECT type,product_id,quantity,price,profit,coalesce(returned_quantity,0),coalesce(tax_amount,0) FROM Transactions WHERE id=?")) {
                    s.setLong(1, transactionId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new ValidationException("العملية غير موجودة");
                        type = r.getString(1);
                        productId = r.getInt(2);
                        qty = r.getDouble(3);
                        price = r.getDouble(4);
                        profit = r.getDouble(5);
                        returnedQty = r.getDouble(6);
                        taxAmount = r.getDouble(7);
                    }
                }

                // ── Reverse stock & batch effects (only for product-related transactions) ──
                if (productId > 0 && qty > 0) {
                    String unitType = productUnitType(c, productId);
                    double actualBatchRemaining = scalar(c, "SELECT coalesce(sum(quantity_remaining),0) FROM Batches WHERE product_id=?", productId);

                    switch (type) {
                        case "IN" -> {
                            // Only reverse what's actually still in batches to prevent negative stock
                            double safeDeduction = Math.min(qty, actualBatchRemaining);
                            double toDelete = safeDeduction;
                            try (PreparedStatement bs = c.prepareStatement(
                                "SELECT id, quantity_remaining FROM Batches WHERE product_id=? AND quantity_remaining>0 ORDER BY date_in DESC, id DESC")) {
                                bs.setInt(1, productId);
                                try (ResultSet br = bs.executeQuery()) {
                                    while (br.next() && toDelete > 0) {
                                        double remaining = br.getDouble(2);
                                        double take = Math.min(toDelete, remaining);
                                        if (take >= remaining - PRICE_EPSILON) {
                                            try (PreparedStatement del = c.prepareStatement("DELETE FROM Batches WHERE id=?")) {
                                                del.setInt(1, br.getInt(1));
                                                del.executeUpdate();
                                            }
                                        } else {
                                            try (PreparedStatement upd = c.prepareStatement(
                                                "UPDATE Batches SET quantity_remaining=quantity_remaining-? WHERE id=?")) {
                                                upd.setDouble(1, take);
                                                upd.setInt(2, br.getInt(1));
                                                upd.executeUpdate();
                                            }
                                        }
                                        toDelete -= take;
                                    }
                                }
                            }
                            stock(c, productId, -safeDeduction);
                        }
                        case "OUT" -> {
                            // Only restore net quantity (sold - already returned)
                            double netQty = Math.max(0, qty - returnedQty);
                            double unitCost = (computeSaleAmount(unitType, qty, price) - profit) / qty;
                            if (unitCost < 0) unitCost = 0;
                            if (netQty > 0) {
                                batch(c, productId, netQty, unitCost);
                                stock(c, productId, netQty);
                                try (PreparedStatement u = c.prepareStatement("UPDATE Products SET is_active=1 WHERE id=? AND is_active=0")) {
                                    u.setInt(1, productId);
                                    u.executeUpdate();
                                }
                            }
                        }
                        case "RETURN" -> {
                            // Only reverse what's actually still available from the returned stock
                            double safeDeduction = Math.min(qty, actualBatchRemaining);
                            double toDelete = safeDeduction;
                            try (PreparedStatement bs = c.prepareStatement(
                                "SELECT id, quantity_remaining FROM Batches WHERE product_id=? AND quantity_remaining>0 ORDER BY date_in DESC, id DESC")) {
                                bs.setInt(1, productId);
                                try (ResultSet br = bs.executeQuery()) {
                                    while (br.next() && toDelete > 0) {
                                        double remaining = br.getDouble(2);
                                        double take = Math.min(toDelete, remaining);
                                        if (take >= remaining - PRICE_EPSILON) {
                                            try (PreparedStatement del = c.prepareStatement("DELETE FROM Batches WHERE id=?")) {
                                                del.setInt(1, br.getInt(1));
                                                del.executeUpdate();
                                            }
                                        } else {
                                            try (PreparedStatement upd = c.prepareStatement(
                                                "UPDATE Batches SET quantity_remaining=quantity_remaining-? WHERE id=?")) {
                                                upd.setDouble(1, take);
                                                upd.setInt(2, br.getInt(1));
                                                upd.executeUpdate();
                                            }
                                        }
                                        toDelete -= take;
                                    }
                                }
                            }
                            stock(c, productId, -safeDeduction);
                        }
                    }
                }

                // ── Reverse cash ledger effects ──
                try (PreparedStatement cl = c.prepareStatement(
                    "DELETE FROM CashLedger WHERE related_transaction_id=?")) {
                    cl.setLong(1, transactionId);
                    cl.executeUpdate();
                }

                // ── Reverse daily card effects (use actual product unit type for WEIGHT products) ──
                try (PreparedStatement dt = c.prepareStatement(
                    "SELECT card_id, type, quantity, price, profit FROM DailyTransactions WHERE transaction_id=?")) {
                    dt.setLong(1, transactionId);
                    try (ResultSet dr = dt.executeQuery()) {
                        while (dr.next()) {
                            int cardId = dr.getInt(1);
                            String dtType = dr.getString(2);
                            double dtQty = dr.getDouble(3);
                            double dtPrice = dr.getDouble(4);
                            double dtProfit = dr.getDouble(5);
                            // Use actual product unit type for correct sale/purchase amount calculation
                            String prodUnitType = (productId > 0) ? productUnitType(c, productId) : "COUNT";
                            switch (dtType) {
                                case "شراء" -> {
                                    double purchaseAmount = computeSaleAmount(prodUnitType, dtQty, dtPrice);
                                    try (PreparedStatement u = c.prepareStatement(
                                        "UPDATE DailyCards SET wholesale=wholesale-?, transaction_count=transaction_count-1 WHERE id=?")) {
                                        u.setDouble(1, purchaseAmount);
                                        u.setInt(2, cardId);
                                        u.executeUpdate();
                                    }
                                }
                                case "بيع" -> {
                                    double saleAmount = computeSaleAmount(prodUnitType, dtQty, dtPrice);
                                    try (PreparedStatement u = c.prepareStatement(
                                        "UPDATE DailyCards SET sales=sales-?, profit=profit-?, transaction_count=transaction_count-1 WHERE id=?")) {
                                        u.setDouble(1, saleAmount);
                                        u.setDouble(2, Math.abs(dtProfit));
                                        u.setInt(3, cardId);
                                        u.executeUpdate();
                                    }
                                }
                                case "مرتجع" -> {
                                    double returnAmount = computeSaleAmount(prodUnitType, dtQty, dtPrice);
                                    try (PreparedStatement u = c.prepareStatement(
                                        "UPDATE DailyCards SET sales=sales+?, profit=profit+?, transaction_count=transaction_count-1 WHERE id=?")) {
                                        u.setDouble(1, returnAmount);
                                        u.setDouble(2, Math.abs(dtProfit));
                                        u.setInt(3, cardId);
                                        u.executeUpdate();
                                    }
                                }
                                case "إيداع" -> {
                                    try (PreparedStatement u = c.prepareStatement(
                                        "UPDATE DailyCards SET deposit=deposit-?, transaction_count=transaction_count-1 WHERE id=?")) {
                                        u.setDouble(1, dtPrice);
                                        u.setInt(2, cardId);
                                        u.executeUpdate();
                                    }
                                }
                                case "سحب" -> {
                                    try (PreparedStatement u = c.prepareStatement(
                                        "UPDATE DailyCards SET withdrawal=withdrawal-?, transaction_count=transaction_count-1 WHERE id=?")) {
                                        u.setDouble(1, dtPrice);
                                        u.setInt(2, cardId);
                                        u.executeUpdate();
                                    }
                                }
                            }
                        }
                    }
                }

                // Mark DailyTransaction entries as cancelled (keep for audit trail)
                try (PreparedStatement dd = c.prepareStatement(
                    "UPDATE DailyTransactions SET notes=coalesce(notes||' | ','')||'ملغية' WHERE transaction_id=?")) {
                    dd.setLong(1, transactionId);
                    dd.executeUpdate();
                }

                // Soft-delete the transaction (mark as CANCELLED to preserve audit history)
                try (PreparedStatement td = c.prepareStatement(
                    "UPDATE Transactions SET type='CANCELLED', notes=coalesce(notes||' | ','')||'ملغية' WHERE id=?")) {
                    td.setLong(1, transactionId);
                    td.executeUpdate();
                }

                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    private String productNameById(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT name FROM Products WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : ""; }
        }
    }

    private static DailyCard cardFromResultSet(ResultSet r) throws SQLException {
        return new DailyCard(r.getInt(1), r.getString(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getInt(9), r.getInt(10) == 1);
    }

    private void validate(int id, double q) {
        if (id <= 0 || q <= 0) throw new ValidationException("أدخل كود منتج وكمية صحيحة");
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT unit_type FROM Products WHERE id=? AND is_active=1")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) throw new ValidationException("المنتج غير موجود");
                String ut = r.getString(1);
                if ((UnitType.COUNT.name().equals(ut) || UnitType.PACKAGE.name().equals(ut)) && q != Math.rint(q)) throw new ValidationException("الكمية يجب أن تكون رقمًا صحيحًا");
            }
        } catch (SQLException e) { throw fail(e); }
    }

    private static void batch(Connection c, int id, double q, double cost) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO Batches(product_id,quantity_remaining,wholesale_price,date_in) VALUES(?,?,?," + NOW + ")")) { s.setInt(1, id); s.setDouble(2, q); s.setDouble(3, cost); s.executeUpdate(); }
    }

    private static void stock(Connection c, int id, double q) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE Stock SET total_quantity=total_quantity+? WHERE product_id=?")) { s.setDouble(1, q); s.setInt(2, id); s.executeUpdate(); }
    }

    private static long transaction(Connection c, int id, String type, double q, double p, double profit, String note) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO Transactions(product_id,type,quantity,price,profit,date_time,notes) VALUES(?,?,?,?,?," + NOW + ",?)", Statement.RETURN_GENERATED_KEYS)) {
            s.setInt(1, id); s.setString(2, type); s.setDouble(3, q); s.setDouble(4, p); s.setDouble(5, profit); s.setString(6, note); s.executeUpdate();
            try (ResultSet r = s.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    private static void ledger(Connection c, String type, double amount, Long t, String note) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO CashLedger(type,amount,date_time,related_transaction_id,notes,person_name) VALUES(?,?," + NOW + ",?,?,'')")) { s.setString(1, type); s.setDouble(2, amount); s.setObject(3, t); s.setString(4, note); s.executeUpdate(); }
    }

    private static void ledger(Connection c, String type, double amount, Long t, String note, String personName) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO CashLedger(type,amount,date_time,related_transaction_id,notes,person_name) VALUES(?,?," + NOW + ",?,?,?)")) { s.setString(1, type); s.setDouble(2, amount); s.setObject(3, t); s.setString(4, note); s.setString(5, personName != null ? personName : ""); s.executeUpdate(); }
    }

    private static double scalar(Connection c, String sql, Integer id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { if (id != null) s.setInt(1, id); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getDouble(1) : 0; } }
    }

    private static double scalar(Connection c, String sql, Integer id, String p1, String p2) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, p1); s.setString(2, p2); try (ResultSet r = s.executeQuery()) { return r.next() ? r.getDouble(1) : 0; } }
    }

    private static Integer ensureNamed(Connection c, String table, String name) throws SQLException {
        if (name == null || name.trim().isEmpty()) return null;
        String clean = name.trim();
        try (PreparedStatement find = c.prepareStatement("SELECT id,name FROM " + table)) {
            try (ResultSet r = find.executeQuery()) { while (r.next()) if (normalize(r.getString("name")).equals(normalize(clean))) return r.getInt("id"); }
        }
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table + "(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) { insert.setString(1, clean); insert.executeUpdate(); try (ResultSet r = insert.getGeneratedKeys()) { r.next(); return r.getInt(1); } }
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private static double price(double n) { if (n < 0) throw new ValidationException("السعر غير صحيح"); return n; }

    private static double computeSaleAmount(String unitType, double quantity, double pricePerUnit) {
        if (UnitType.WEIGHT.name().equals(unitType)) return (quantity / 1000.0) * pricePerUnit;
        return quantity * pricePerUnit;
    }

    private String productUnitType(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT unit_type FROM Products WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : UnitType.COUNT.name(); }
        }
    }

    private String productMeasurementUnit(Connection c, int id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT coalesce(measurement_unit,'') FROM Products WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : ""; }
        }
    }

    /** Save supplier code and name on a product. */
    private void saveSupplierOnProduct(Connection c, int productId, String supplierCode, String supplierName) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE Products SET supplier_code=?, supplier_name=? WHERE id=?")) {
            s.setString(1, supplierCode != null ? supplierCode : "");
            s.setString(2, supplierName != null ? supplierName : "");
            s.setInt(3, productId);
            s.executeUpdate();
        }
    }

    private static String nextCode(Connection c, String categoryName) throws SQLException {
        // Look up the category's code field from the Categories table
        String prefix = categoryName.trim().toUpperCase(java.util.Locale.ROOT);
        try (PreparedStatement s = c.prepareStatement("SELECT code FROM Categories WHERE name = ?")) {
            s.setString(1, categoryName.trim());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String catCode = r.getString(1);
                    if (catCode != null && !catCode.isEmpty()) {
                        prefix = catCode.toUpperCase(java.util.Locale.ROOT);
                    }
                }
            }
        }
        int prefixLen = prefix.length();
        try (PreparedStatement s = c.prepareStatement("SELECT code FROM Products WHERE code LIKE ? ORDER BY code DESC LIMIT 1")) {
            s.setString(1, prefix + "%");
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String lastCode = r.getString(1);
                    int num = Integer.parseInt(lastCode.substring(prefixLen)) + 1;
                    return String.format("%s%05d", prefix, num).toUpperCase(java.util.Locale.ROOT);
                }
            }
        }
        return (prefix + "00001").toUpperCase(java.util.Locale.ROOT);
    }

    private static RuntimeException fail(Exception e) { return e instanceof RuntimeException x ? x : new IllegalStateException("تعذر إتمام العملية", e); }

    // ========== Customers ==========

    public record Customer(int id, String code, String name, String customerType, String phone, String address, String materials, String createdAt) {}
    public record CustomerTransaction(long id, String type, String productName, double quantity, double price, double total, String dateTime, String notes, String customerName, String customerType, String phone) {}
    public record SupplierTransaction(long id, String type, String productName, double quantity, double price, double total, String dateTime, String notes, String unitType, double packageWeight) {}

    /** Get next customer code: CL0001, CL0002, etc. */
    private static String nextCustomerCode(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT code FROM Customers ORDER BY id DESC LIMIT 1")) {
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String last = r.getString(1);
                    if (last != null && last.startsWith("CL")) {
                        int num = Integer.parseInt(last.substring(2)) + 1;
                        return String.format("CL%04d", num);
                    }
                }
            }
        }
        return "CL0001";
    }

    /** Add a new customer. */
    public int addCustomer(String name, String customerType, String phone, String address, String materials) {
        if (name == null || name.trim().isEmpty()) throw new ValidationException("اسم العميل مطلوب");
        if (phone == null || phone.trim().isEmpty()) throw new ValidationException("رقم التليفون مطلوب");
        if (materials == null || materials.trim().isEmpty()) throw new ValidationException("نوع المواد المتعامل بها مطلوب");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                String code = nextCustomerCode(c);
                try (PreparedStatement s = c.prepareStatement("INSERT INTO Customers(code,name,customer_type,phone,address,materials) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1, code);
                    s.setString(2, name.trim());
                    s.setString(3, customerType != null ? customerType.trim() : "");
                    s.setString(4, phone.trim());
                    s.setString(5, address != null ? address.trim() : "");
                    s.setString(6, materials.trim());
                    s.executeUpdate();
                    try (ResultSet r = s.getGeneratedKeys()) {
                        r.next();
                        int id = r.getInt(1);
                        c.commit();
                        return id;
                    }
                }
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Update customer data (not the code). */
    public void updateCustomer(int id, String name, String customerType, String phone, String address, String materials) {
        if (id <= 0) throw new ValidationException("عميل غير صحيح");
        if (name == null || name.trim().isEmpty()) throw new ValidationException("اسم العميل مطلوب");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE Customers SET name=?,customer_type=?,phone=?,address=?,materials=? WHERE id=?")) {
                s.setString(1, name.trim());
                s.setString(2, customerType != null ? customerType.trim() : "");
                s.setString(3, phone != null ? phone.trim() : "");
                s.setString(4, address != null ? address.trim() : "");
                s.setString(5, materials != null ? materials.trim() : "");
                s.setInt(6, id);
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Delete a customer. */
    public void deleteCustomer(int id) {
        if (id <= 0) throw new ValidationException("عميل غير صحيح");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM CustomerTransactions WHERE customer_id=?")) { s.setInt(1, id); s.executeUpdate(); }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM Customers WHERE id=?")) { s.setInt(1, id); s.executeUpdate(); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Get all customers. */
    public List<Customer> allCustomers() {
        List<Customer> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,code,name,customer_type,phone,address,materials,created_at FROM Customers ORDER BY id DESC")) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Customer(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /**
     * Find customers whose code or name contains the given filter text (case-insensitive).
     * Used for auto-complete suggestions in the sales page.
     */
    public List<Customer> customersByCodeLike(String filter) {
        List<Customer> out = new ArrayList<>();
        if (filter == null || filter.trim().isEmpty()) return out;
        String x = "%" + filter.trim() + "%";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,code,name,customer_type,phone,address,materials,created_at FROM Customers WHERE code LIKE ? COLLATE NOCASE OR name LIKE ? COLLATE NOCASE ORDER BY code LIMIT 20")) {
            s.setString(1, x);
            s.setString(2, x);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Customer(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Find customer by code. Returns null if not found. Case-insensitive. */
    public Customer customerByCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,code,name,customer_type,phone,address,materials,created_at FROM Customers WHERE code=? COLLATE NOCASE")) {
            s.setString(1, code.trim());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new Customer(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** customerByCode using an existing transaction connection */
    private Customer customerByCode(Connection c, String code) {
        if (code == null || code.trim().isEmpty()) return null;
        try (PreparedStatement s = c.prepareStatement("SELECT id,code,name,customer_type,phone,address,materials,created_at FROM Customers WHERE code=? COLLATE NOCASE")) {
            s.setString(1, code.trim());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new Customer(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Record a return transaction under a customer (called from returnsPage). */
    /** Get all transactions for a specific customer. */
    /** Get transactions associated with a supplier (incoming purchases). */
    public List<SupplierTransaction> supplierTransactions(int supplierId) {
        List<SupplierTransaction> out = new ArrayList<>();
        String sql = "SELECT t.id,t.type,coalesce(p.name,''),t.quantity,coalesce(t.price,0),0,coalesce(t.date_time,''),coalesce(t.notes,''),coalesce(p.unit_type,'COUNT'),coalesce(p.package_weight,0)"
            + " FROM Transactions t"
            + " JOIN Products p ON p.id=t.product_id"
            + " WHERE p.supplier_code = (SELECT code FROM Suppliers WHERE id=?) AND t.type='IN'"
            + " ORDER BY t.date_time DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setInt(1, supplierId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    double qty = r.getDouble(4), price = r.getDouble(5);
                    String unitType = r.getString(9);
                    double pkgW = r.getDouble(10);
                    double total = computeSaleAmount(unitType, qty, price);
                    out.add(new SupplierTransaction(r.getLong(1), r.getString(2), r.getString(3), qty, price, total, r.getString(7), r.getString(8), r.getString(9), pkgW));
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    public List<CustomerTransaction> customerTransactions(int customerId) {
        List<CustomerTransaction> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,type,product_name,quantity,price,total,date_time,notes FROM CustomerTransactions WHERE customer_id=? ORDER BY date_time DESC")) {
            s.setInt(1, customerId);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new CustomerTransaction(r.getLong(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), r.getString(8), "", "", ""));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get daily transactions for the current card, with customer code/name if available. */
    public record DailyTransactionWithCustomer(String time, String type, String productName, double quantity, double price, double profit, String notes, String customerCode, String customerName) {}

    public List<DailyTransactionWithCustomer> todayTransactionsWithCustomer() {
        DailyCard today = ensureTodayCard();
        List<DailyTransactionWithCustomer> out = new ArrayList<>();
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT dt.time_stamp,dt.type,dt.product_name,dt.quantity,dt.price,dt.profit,coalesce(dt.notes,'') "
            + "FROM DailyTransactions dt WHERE dt.card_id=? ORDER BY dt.time_stamp DESC")) {
            s.setInt(1, today.id());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new DailyTransactionWithCustomer(
                    r.getString(1), r.getString(2), r.getString(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getString(7), "", ""));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    // ========== Column Preferences (order + width per table) ==========

    /** Save column order and widths for a specific table. */
    public void saveColumnPrefs(String tableName, String order, String widths) {
        if (tableName == null || tableName.isBlank()) return;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "INSERT OR REPLACE INTO ColumnPrefs(table_name,column_order,column_widths) VALUES(?,?,?)")) {
            s.setString(1, tableName.trim());
            s.setString(2, order != null ? order : "");
            s.setString(3, widths != null ? widths : "");
            s.executeUpdate();
        } catch (SQLException e) { throw fail(e); }
    }

    /** Load saved column order and widths for a specific table. Returns [order, widths] or null. */
    public String[] loadColumnPrefs(String tableName) {
        if (tableName == null || tableName.isBlank()) return null;
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT column_order,column_widths FROM ColumnPrefs WHERE table_name=?")) {
            s.setString(1, tableName.trim());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String order = r.getString(1);
                    String widths = r.getString(2);
                    if (order != null && !order.isEmpty()) return new String[]{order, widths != null ? widths : ""};
                }
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    public void resetAllData() {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("DELETE FROM DailyTransactions");
                s.executeUpdate("DELETE FROM DailyCards");
                s.executeUpdate("DELETE FROM MonthlyReports");
                s.executeUpdate("DELETE FROM YearlyReports");
                s.executeUpdate("DELETE FROM CustomerTransactions");
                s.executeUpdate("DELETE FROM Customers");
                s.executeUpdate("DELETE FROM CashLedger");
                s.executeUpdate("DELETE FROM Price_History");
                s.executeUpdate("DELETE FROM Transactions");
                s.executeUpdate("DELETE FROM Batches");
                s.executeUpdate("DELETE FROM Stock");
                s.executeUpdate("DELETE FROM Products");
                s.executeUpdate("DELETE FROM Companies");
                s.executeUpdate("DELETE FROM Categories");
                s.executeUpdate("DELETE FROM Suppliers");
                s.executeUpdate("DELETE FROM DailyReportArchives");
                s.executeUpdate("DELETE FROM MonthlyReportArchives");
                s.executeUpdate("DELETE FROM Tools");
                s.executeUpdate("DELETE FROM Settings");
                s.executeUpdate("DELETE FROM ColumnPrefs");
                c.commit();
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUPPLIERS
    // ═══════════════════════════════════════════════════════════════

    public record Supplier(int id, String code, String name, String phone, String specialty, String address, String createdAt,
                          double totalPurchases, double totalPurchaseValue, String lastPurchaseDate) {}

    public record SupplierStatistics(int totalPurchases, double totalPurchaseValue, String lastPurchaseDate) {}

    /** Add a new supplier. */
    public int addSupplier(String name, String phone, String specialty, String address) {
        if (name == null || name.trim().isEmpty()) throw new ValidationException("اسم المورد مطلوب");
        if (phone == null || phone.trim().isEmpty()) throw new ValidationException("رقم الهاتف مطلوب");
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                String code = nextSupplierCode(c);
                try (PreparedStatement s = c.prepareStatement("INSERT INTO Suppliers(code,name,phone,specialty,address) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    s.setString(1, code);
                    s.setString(2, name.trim());
                    s.setString(3, phone.trim());
                    s.setString(4, specialty != null ? specialty.trim() : "");
                    s.setString(5, address != null ? address.trim() : "");
                    s.executeUpdate();
                    try (ResultSet r = s.getGeneratedKeys()) {
                        r.next();
                        int id = r.getInt(1);
                        c.commit();
                        return id;
                    }
                }
            } catch (Exception e) { c.rollback(); throw fail(e); }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Update supplier data (not the code). */
    public void updateSupplier(int id, String name, String phone, String specialty, String address) {
        if (id <= 0) throw new ValidationException("مورد غير صحيح");
        if (name == null || name.trim().isEmpty()) throw new ValidationException("اسم المورد مطلوب");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("UPDATE Suppliers SET name=?,phone=?,specialty=?,address=? WHERE id=?")) {
                s.setString(1, name.trim());
                s.setString(2, phone != null ? phone.trim() : "");
                s.setString(3, specialty != null ? specialty.trim() : "");
                s.setString(4, address != null ? address.trim() : "");
                s.setInt(5, id);
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Delete a supplier. */
    public void deleteSupplier(int id) {
        if (id <= 0) throw new ValidationException("مورد غير صحيح");
        try (Connection c = DatabaseManager.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM Suppliers WHERE id=?")) {
                s.setInt(1, id);
                s.executeUpdate();
            }
        } catch (SQLException e) { throw fail(e); }
    }

    /** Get all suppliers. */
    public List<Supplier> allSuppliers() {
        List<Supplier> out = new ArrayList<>();
        String sql = "SELECT id,code,name,phone,coalesce(specialty,''),coalesce(address,''),coalesce(created_at,''),"
            + "coalesce(total_purchases,0),coalesce(total_purchase_value,0),coalesce(last_purchase_date,'')"
            + " FROM Suppliers ORDER BY id DESC";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Supplier(r.getInt(1), r.getString(2), r.getString(3), r.getString(4),
                    r.getString(5), r.getString(6), r.getString(7), r.getDouble(8), r.getDouble(9), r.getString(10)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Find suppliers by code (prefix match, case-insensitive). */
    public List<Supplier> suppliersByCodeLike(String filter) {
        if (filter == null || filter.trim().isEmpty()) return allSuppliers();
        String x = "%" + filter.trim() + "%";
        List<Supplier> out = new ArrayList<>();
        String sql = "SELECT id,code,name,phone,coalesce(specialty,''),coalesce(address,''),coalesce(created_at,''),"
            + "coalesce(total_purchases,0),coalesce(total_purchase_value,0),coalesce(last_purchase_date,'')"
            + " FROM Suppliers WHERE code LIKE ? COLLATE NOCASE OR name LIKE ? COLLATE NOCASE ORDER BY id DESC LIMIT 20";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, x); s.setString(2, x);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new Supplier(r.getInt(1), r.getString(2), r.getString(3), r.getString(4),
                    r.getString(5), r.getString(6), r.getString(7), r.getDouble(8), r.getDouble(9), r.getString(10)));
            }
        } catch (SQLException e) { throw fail(e); }
        return out;
    }

    /** Get supplier by code (case-insensitive). */
    public Supplier supplierByCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String sql = "SELECT id,code,name,phone,coalesce(specialty,''),coalesce(address,''),coalesce(created_at,''),"
            + "coalesce(total_purchases,0),coalesce(total_purchase_value,0),coalesce(last_purchase_date,'')"
            + " FROM Suppliers WHERE code=? COLLATE NOCASE";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, code.trim());
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) return new Supplier(r.getInt(1), r.getString(2), r.getString(3), r.getString(4),
                    r.getString(5), r.getString(6), r.getString(7), r.getDouble(8), r.getDouble(9), r.getString(10));
            }
        } catch (SQLException e) { throw fail(e); }
        return null;
    }

    /** Get supplier statistics — reads from the supplier record's stored columns. */
    public SupplierStatistics getSupplierStatistics(int supplierId) {
        // Currently the system does not track which products came from which supplier
        // at the transaction level. Statistics are stored in the Suppliers table
        // columns (total_purchases, total_purchase_value, last_purchase_date)
        // and will be populated when supplier-product linking is implemented.
        // For now, return empty stats.
        return new SupplierStatistics(0, 0, "");
    }

    /** Generate next supplier code (SP001, SP002, ...). */
    private static String nextSupplierCode(Connection c) throws SQLException {
        int max = 0;
        try (PreparedStatement s = c.prepareStatement("SELECT code FROM Suppliers ORDER BY id DESC LIMIT 1")) {
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    String last = r.getString(1);
                    if (last != null && last.startsWith("SP")) {
                        try { max = Integer.parseInt(last.substring(2)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return String.format("SP%03d", max + 1);
    }
}
