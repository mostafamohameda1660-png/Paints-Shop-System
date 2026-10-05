package com.shop.database;

import java.sql.Connection;
import java.sql.Statement;

public class DatabaseInitializer {

    public static void initialize() {
        String[] queries = {
            "PRAGMA foreign_keys = ON",
            "CREATE TABLE IF NOT EXISTS Companies(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS Categories(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS Products(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE NOT NULL, company_id INTEGER, category_id INTEGER, name TEXT NOT NULL, size TEXT, unit_type TEXT NOT NULL, default_wholesale_price REAL DEFAULT 0, default_selling_price REAL DEFAULT 0, package_weight REAL DEFAULT 0, notes TEXT, is_active INTEGER DEFAULT 1, FOREIGN KEY(company_id) REFERENCES Companies(id), FOREIGN KEY(category_id) REFERENCES Categories(id))",
            "CREATE TABLE IF NOT EXISTS Batches(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, quantity_remaining REAL NOT NULL, wholesale_price REAL NOT NULL, date_in TEXT NOT NULL, FOREIGN KEY(product_id) REFERENCES Products(id))",
            "CREATE TABLE IF NOT EXISTS Stock(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER UNIQUE, total_quantity REAL DEFAULT 0, FOREIGN KEY(product_id) REFERENCES Products(id))",
            "CREATE TABLE IF NOT EXISTS Transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER, batch_id INTEGER, type TEXT NOT NULL, quantity REAL, price REAL, profit REAL, date_time TEXT NOT NULL, notes TEXT, returned_quantity REAL NOT NULL DEFAULT 0, FOREIGN KEY(product_id) REFERENCES Products(id), FOREIGN KEY(batch_id) REFERENCES Batches(id))",
            "CREATE TABLE IF NOT EXISTS CashLedger(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, amount REAL NOT NULL, date_time TEXT NOT NULL, related_transaction_id INTEGER, notes TEXT, FOREIGN KEY(related_transaction_id) REFERENCES Transactions(id))",
            "CREATE TABLE IF NOT EXISTS Price_History(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER NOT NULL, price_type TEXT NOT NULL, old_price REAL, new_price REAL, date TEXT NOT NULL, FOREIGN KEY(product_id) REFERENCES Products(id))",
            "CREATE TABLE IF NOT EXISTS DailyCards(id INTEGER PRIMARY KEY AUTOINCREMENT, card_date TEXT NOT NULL UNIQUE, sales REAL DEFAULT 0, profit REAL DEFAULT 0, wholesale REAL DEFAULT 0, balance REAL DEFAULT 0, deposit REAL DEFAULT 0, withdrawal REAL DEFAULT 0, transaction_count INTEGER DEFAULT 0, is_closed INTEGER DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS DailyTransactions(id INTEGER PRIMARY KEY AUTOINCREMENT, card_id INTEGER NOT NULL, transaction_id INTEGER NOT NULL, type TEXT NOT NULL, product_name TEXT, quantity REAL, price REAL, profit REAL, unit_type TEXT DEFAULT 'COUNT', time_stamp TEXT NOT NULL, notes TEXT, FOREIGN KEY(card_id) REFERENCES DailyCards(id), FOREIGN KEY(transaction_id) REFERENCES Transactions(id))",
            "CREATE TABLE IF NOT EXISTS Settings(id INTEGER PRIMARY KEY, shop_name TEXT DEFAULT 'محل الدهانات والحدادة', backup_path TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_product_code ON Products(code)",
            "CREATE INDEX IF NOT EXISTS idx_product_name ON Products(name)",
            "CREATE INDEX IF NOT EXISTS idx_product_company ON Products(company_id)",
            "CREATE INDEX IF NOT EXISTS idx_transaction_date ON Transactions(date_time)",
            "CREATE INDEX IF NOT EXISTS idx_daily_card_date ON DailyCards(card_date)",
            "CREATE INDEX IF NOT EXISTS idx_daily_tx_card ON DailyTransactions(card_id)",
            "INSERT OR IGNORE INTO Settings(id) VALUES(1)"
        };

        try (Connection conn = DatabaseManager.getConnection(); Statement stmt = conn.createStatement()) {
            for (String query : queries) {
                if (!query.trim().isEmpty()) {
                    stmt.execute(query);
                }
            }
            migrateIfNeeded(stmt);
        } catch (Exception e) {
            throw new RuntimeException("فشل تهيئة قاعدة البيانات", e);
        }
    }

    private static void migrateIfNeeded(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE DailyCards ADD COLUMN deposit REAL DEFAULT 0");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE DailyCards ADD COLUMN withdrawal REAL DEFAULT 0");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE DailyCards ADD COLUMN is_closed INTEGER DEFAULT 0");
        } catch (Exception ignored) {}
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS DailyTransactions(id INTEGER PRIMARY KEY AUTOINCREMENT, card_id INTEGER NOT NULL, transaction_id INTEGER NOT NULL, type TEXT NOT NULL, product_name TEXT, quantity REAL, price REAL, profit REAL, time_stamp TEXT NOT NULL, notes TEXT, FOREIGN KEY(card_id) REFERENCES DailyCards(id), FOREIGN KEY(transaction_id) REFERENCES Transactions(id))");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE Products ADD COLUMN package_weight REAL DEFAULT 0");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE DailyTransactions ADD COLUMN unit_type TEXT DEFAULT 'COUNT'");
        } catch (Exception ignored) {}
        // ── Customers table ──
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS Customers(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE NOT NULL, name TEXT NOT NULL, customer_type TEXT DEFAULT '', phone TEXT NOT NULL, address TEXT DEFAULT '', materials TEXT NOT NULL, created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
        // ── CustomerTransactions table (links sales/returns to customers) ──
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS CustomerTransactions(id INTEGER PRIMARY KEY AUTOINCREMENT, customer_id INTEGER NOT NULL, transaction_id INTEGER, type TEXT NOT NULL, product_name TEXT DEFAULT '', quantity REAL DEFAULT 0, price REAL DEFAULT 0, total REAL DEFAULT 0, date_time TEXT DEFAULT (datetime('now','localtime')), notes TEXT DEFAULT '', FOREIGN KEY(customer_id) REFERENCES Customers(id))");
        } catch (Exception ignored) {}
        migrateDailyTransactionsCustomerColumns(stmt);
        migrateTaxColumn(stmt);
        migrateMeasurementUnit(stmt);
        migrateCategoryActive(stmt);
        migrateUnitName(stmt);
        migrateUnitCount(stmt);
        migrateCreatedAt(stmt);
        migrateColumnPrefs(stmt);
        migratePersonName(stmt);
        migrateDailyTransactionsMeasurementUnit(stmt);
        migrateToolsTable(stmt);
        migrateSuppliersTable(stmt);
        migrateSupplierCode(stmt);
        migrateDailyReportArchivesTable(stmt);
        migrateDailyReportArchivesSummaryColumns(stmt);
        migrateMonthlyReportsTable(stmt);
        migrateMonthlyReportArchivesTable(stmt);
        migrateYearlyReportsTable(stmt);

        try {
    stmt.execute("ALTER TABLE Transactions ADD COLUMN returned_quantity REAL NOT NULL DEFAULT 0");
} catch (Exception ignored) {}
    }

    private static void migrateSupplierCode(Statement stmt) {
        try { stmt.execute("ALTER TABLE Products ADD COLUMN supplier_code TEXT DEFAULT ''"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE Products ADD COLUMN supplier_name TEXT DEFAULT ''"); } catch (Exception ignored) {}
    }

    private static void migrateDailyTransactionsMeasurementUnit(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE DailyTransactions ADD COLUMN measurement_unit TEXT DEFAULT ''");
        } catch (Exception ignored) {}
    }

    private static void migrateToolsTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS Tools(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, size TEXT DEFAULT '', renter_name TEXT NOT NULL, rental_price REAL NOT NULL, notes TEXT DEFAULT '', code TEXT DEFAULT '', status TEXT DEFAULT 'جاهزة', rental_date TEXT DEFAULT '', created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE Tools ADD COLUMN code TEXT DEFAULT ''"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE Tools ADD COLUMN status TEXT DEFAULT 'جاهزة'"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE Tools ADD COLUMN rental_date TEXT DEFAULT ''"); } catch (Exception ignored) {}
    }

    private static void migrateDailyReportArchivesTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS DailyReportArchives(id INTEGER PRIMARY KEY AUTOINCREMENT, report_date TEXT NOT NULL UNIQUE, snapshot_data TEXT NOT NULL, created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
    }

    private static void migrateDailyReportArchivesSummaryColumns(Statement stmt) {
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN sale_count INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN purchase_count INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN return_count INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN total_sales REAL DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN total_purchases REAL DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN total_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
        try { stmt.execute("ALTER TABLE DailyReportArchives ADD COLUMN net_movement REAL DEFAULT 0"); } catch (Exception ignored) {}
    }

    private static void migrateMonthlyReportsTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS MonthlyReports(id INTEGER PRIMARY KEY AUTOINCREMENT, year_month TEXT NOT NULL UNIQUE, total_sales REAL DEFAULT 0, total_profit REAL DEFAULT 0, total_wholesale REAL DEFAULT 0, total_deposit REAL DEFAULT 0, total_withdrawal REAL DEFAULT 0, total_transactions INTEGER DEFAULT 0, closing_balance REAL DEFAULT 0, days_count INTEGER DEFAULT 0, created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
    }

    private static void migrateMonthlyReportArchivesTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS MonthlyReportArchives(id INTEGER PRIMARY KEY AUTOINCREMENT, year_month TEXT NOT NULL UNIQUE, snapshot_data TEXT NOT NULL, days_count INTEGER DEFAULT 0, created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
    }

    private static void migrateYearlyReportsTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS YearlyReports(id INTEGER PRIMARY KEY AUTOINCREMENT, year INTEGER NOT NULL UNIQUE, total_sales REAL DEFAULT 0, total_profit REAL DEFAULT 0, total_wholesale REAL DEFAULT 0, total_deposit REAL DEFAULT 0, total_withdrawal REAL DEFAULT 0, total_transactions INTEGER DEFAULT 0, closing_balance REAL DEFAULT 0, months_count INTEGER DEFAULT 0, created_at TEXT DEFAULT (datetime('now','localtime')))");
        } catch (Exception ignored) {}
    }

    private static void migrateSuppliersTable(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS Suppliers(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, specialty TEXT DEFAULT '', address TEXT DEFAULT '', created_at TEXT DEFAULT (datetime('now','localtime')))");
            // Add supplier_statistics columns if Suppliers table already existed
            try {
                stmt.execute("ALTER TABLE Suppliers ADD COLUMN total_purchases REAL DEFAULT 0");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE Suppliers ADD COLUMN total_purchase_value REAL DEFAULT 0");
            } catch (Exception ignored) {}
            try {
                stmt.execute("ALTER TABLE Suppliers ADD COLUMN last_purchase_date TEXT DEFAULT ''");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    private static void migratePersonName(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE CashLedger ADD COLUMN person_name TEXT DEFAULT ''");
        } catch (Exception ignored) {}
    }

    private static void migrateColumnPrefs(Statement stmt) {
        try {
            stmt.execute("CREATE TABLE IF NOT EXISTS ColumnPrefs(table_name TEXT PRIMARY KEY, column_order TEXT NOT NULL, column_widths TEXT NOT NULL)");
        } catch (Exception ignored) {}
    }

    private static void migrateCreatedAt(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE Products ADD COLUMN created_at TEXT DEFAULT ''");
        } catch (Exception ignored) {}
        try {
            // For existing products without a created_at, set to first batch date (with time) or current datetime
            stmt.execute("UPDATE Products SET created_at = coalesce("
                + "(SELECT min(date_in) FROM Batches WHERE product_id=Products.id),"
                + " datetime('now','localtime')"
                + ") WHERE created_at IS NULL OR created_at = ''");
        } catch (Exception ignored) {}
        try {
            // Fix date-only values (e.g. "2026-01-15") by appending time so the display can parse them
            stmt.execute("UPDATE Products SET created_at = created_at || ' 00:00:00' WHERE length(created_at) = 10 AND instr(created_at, ' ') = 0 AND created_at != ''");
        } catch (Exception ignored) {}
    }

    private static void migrateUnitCount(Statement stmt) {
        try { stmt.execute("ALTER TABLE Stock ADD COLUMN unit_count REAL DEFAULT 0"); } catch (Exception ignored) {}
    }

    private static void migrateUnitName(Statement stmt) {
        try { stmt.execute("ALTER TABLE Products ADD COLUMN unit_name TEXT DEFAULT ''"); } catch (Exception ignored) {}
    }

    private static void migrateCategoryActive(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE Categories ADD COLUMN is_active INTEGER DEFAULT 1");
        } catch (Exception ignored) {}
    }

    private static void migrateMeasurementUnit(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE Products ADD COLUMN measurement_unit TEXT DEFAULT ''");
        } catch (Exception ignored) {}
    }

    private static void migrateTaxColumn(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE Transactions ADD COLUMN tax_amount REAL DEFAULT 0");
        } catch (Exception ignored) {}
    }

    private static void migrateDailyTransactionsCustomerColumns(Statement stmt) {
        try {
            stmt.execute("ALTER TABLE DailyTransactions ADD COLUMN customer_code TEXT DEFAULT ''");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE DailyTransactions ADD COLUMN customer_name TEXT DEFAULT ''");
        } catch (Exception ignored) {}
        // ── Categories new columns: code, units, measurement_units ──
        try {
            stmt.execute("ALTER TABLE Categories ADD COLUMN code TEXT DEFAULT ''");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE Categories ADD COLUMN units TEXT DEFAULT ''");
        } catch (Exception ignored) {}
        try {
            stmt.execute("ALTER TABLE Categories ADD COLUMN measurement_units TEXT DEFAULT ''");
        } catch (Exception ignored) {}
    }
}
