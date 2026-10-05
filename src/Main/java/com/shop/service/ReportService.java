package com.shop.service;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import com.shop.service.InventoryService.CashLedgerRow;
import com.shop.service.InventoryService.Customer;
import com.shop.service.InventoryService.CustomerTransaction;
import com.shop.service.InventoryService.DailyCardRow;
import com.shop.service.InventoryService.ProductRow;
import com.shop.service.InventoryService.SaleRow;
import com.shop.service.InventoryService.TransactionRow;

/**
 * Corporate Minimal PDF Report Generator.
 * Produces clean, elegant, professional reports with a single navy accent,
 * thin borders, no charts, no decorative elements, and consistent Arabic support.
 * Unified visual identity across all report types.
 */
public final class ReportService {

    // ═══════════════════════════════════════════════════════════════
    // COLOR SCHEME — Corporate Minimal (Navy + White)
    // ═══════════════════════════════════════════════════════════════
    private static final Color C_PRIMARY     = new Color(20, 42, 74);    // Deep corporate navy
    private static final Color C_PRIMARY_DARK= new Color(14, 30, 55);    // Darker navy for accents
    private static final Color C_ACCENT      = new Color(35, 75, 130);   // Medium blue accent
    private static final Color C_GOLD        = new Color(180, 150, 80);  // Gold accent for elegance
    private static final Color C_TEXT        = new Color(35, 35, 35);     // Near black
    private static final Color C_TEXT_SEC    = new Color(100, 100, 100);  // Gray for secondary text
    private static final Color C_BORDER      = new Color(200, 205, 215);  // Light gray border
    private static final Color C_HEADER_BG   = new Color(225, 233, 245);  // Subtle blue-gray table header
    private static final Color C_WHITE       = Color.WHITE;
    private static final Color C_ROW_ALT     = new Color(245, 248, 252);  // Subtle alternating row
    private static final Color C_FRAME       = new Color(18, 38, 68);     // Frame border color

    // ═══════════════════════════════════════════════════════════════
    // LAYOUT CONSTANTS
    // ═══════════════════════════════════════════════════════════════
    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float MARGIN = 42;
    private static final float FRAME_INNER = 8;  // Frame inner padding
    private static final float CONTENT_W = PAGE_W - 2 * MARGIN;
    private static final float FOOTER_H = 45;
    private static final float BOTTOM_Y = MARGIN + FOOTER_H;
    private static final float LINE_H = 20;

    // ═══════════════════════════════════════════════════════════════
    // FIELDS
    // ═══════════════════════════════════════════════════════════════
    private final InventoryService service;

    private static final ArabicShaping SHAPER;
    static {
        try { SHAPER = new ArabicShaping(ArabicShaping.LETTERS_SHAPE); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    public ReportService(InventoryService service) { this.service = service; }

    // ═══════════════════════════════════════════════════════════════
    // PDF CONTEXT — holds mutable state during report generation
    // ═══════════════════════════════════════════════════════════════
    private static class PdfCtx {
        PDDocument doc;
        PDType0Font font;
        PDPageContentStream cs;
        float y;
        List<PDPage> pages = new ArrayList<>();
        LocalDate reportDate;
        LocalDate reportEndDate; // for period reports
        String reportTitle;
        String reportType;
        String shopName    = "مؤسسة الحمد للمقاولات و التوريدات";

        /** Returns the available content width between the left and right margins. */
        float getContentWidth() { return CONTENT_W; }
    }

    // ═══════════════════════════════════════════════════════════════
    // PUBLIC REPORT METHODS
    // ═══════════════════════════════════════════════════════════════

    public void exportDailyPdf(Path path) { exportDailyPdf(path, LocalDate.now()); }

    public void exportDailyPdf(Path path, LocalDate date) {
        DailyCardRow card = service.getDailyCard(date);
        List<TransactionRow> transactions = service.getTransactionsForDate(date);
        List<ProductRow> stock = service.products("");
        List<CashLedgerRow> cashOps = service.getAllCashLedger();
        List<CustomerTransaction> customerTx = service.getCustomerTransactionsForDate(date);
        List<InventoryService.SupplierDailyRecord> supplierTx = service.getSupplierTransactionsForDate(date);

        List<TransactionRow> sales = transactions.stream()
                .filter(t -> "بيع".equals(t.type())).toList();
        List<TransactionRow> purchases = transactions.stream()
                .filter(t -> "شراء".equals(t.type())).toList();
        List<TransactionRow> returnsList = transactions.stream()
                .filter(t -> "مرتجع".equals(t.type())).toList();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = date;
            ctx.reportEndDate = date;
            ctx.reportTitle = "التقرير اليومي ";
            ctx.reportType  = "تقرير يومي";

            newPage(ctx);

            // ── Summary Table ──
            ctx.y = drawSummarySection(ctx, card, transactions.size(), stock.size());
            ctx.y -= 8;

            // ── Sales Table ──
            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, " المبيعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, sales);
                ctx.y -= 10;
            }

            // ── Purchases Table ──
            if (!purchases.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المشتريات / الوارد", ctx.y);
                ctx.y -= 4;
                ctx.y = drawPurchasesTable(ctx, purchases);
                ctx.y -= 10;
            }

            // ── Returns Table ──
            if (!returnsList.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المرتجعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, returnsList);
                ctx.y -= 10;
            }

            // ── Current Stock Table ──
            if (!stock.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المنتجات الموجودة في المخزن", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, stock);
                ctx.y -= 10;
            }

            // ── Cash Ledger Table ──
            if (!cashOps.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات الخزينة (السحب والإيداع)", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCashTable(ctx, cashOps);
                ctx.y -= 10;
            }

            // ── All Transactions Table ──
            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع معاملات اليوم", ctx.y);
                ctx.y -= 4;
                ctx.y = drawAllTxTable(ctx, transactions);
                ctx.y -= 10;
            }

            // ── Customer Transactions Table ──
            if (!customerTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات العملاء", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCustomerTxTable(ctx, customerTx);
                ctx.y -= 10;
            }

            // ── Supplier Transactions Table ──
            if (!supplierTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات الموردين", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSupplierTxTable(ctx, supplierTx);
                ctx.y -= 10;
            }

            // ── Tool Rentals Table (today only) ──
            java.util.List<InventoryService.RentalRecord> todayRentals = service.getAllRentals().stream()
                .filter(r -> r.date() != null && r.date().equals(date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))))
                .toList();
            if (!todayRentals.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات تأجير العدد", ctx.y);
                ctx.y -= 4;
                String[] rentalHeaders = {"الوقت", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الربح"};
                String[][] rentalData = new String[todayRentals.size()][];
                for (int i = 0; i < todayRentals.size(); i++) {
                    InventoryService.RentalRecord r = todayRentals.get(i);
                    rentalData[i] = new String[]{
                        r.time(),
                        r.toolCode() != null && !r.toolCode().isEmpty() ? r.toolCode() : "—",
                        r.toolName(),
                        r.size() != null && !r.size().isEmpty() ? r.size() : "—",
                        r.renterName() != null && !r.renterName().isEmpty() ? r.renterName() : "—",
                        moneyClean(r.price()),
                        moneyClean(r.price())
                    };
                }
                ctx.y = drawTable(ctx, rentalHeaders, null, java.util.Arrays.asList(rentalData));
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير التقرير إلى PDF", e);
        }
    }

    public void exportTransactionsPdf(Path path) { exportTransactionsPdf(path, LocalDate.now()); }

    public void exportTransactionsPdf(Path path, LocalDate date) {
        List<TransactionRow> transactions = service.getTransactionsForDate(date);
        DailyCardRow card = service.getDailyCard(date);

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = date;
            ctx.reportEndDate = date;
            ctx.reportTitle = "تقرير المعاملات اليومية";
            ctx.reportType  = "تقرير معاملات";

            newPage(ctx);

            // ── Summary ──
            ctx.y = drawSummarySection(ctx, card, transactions.size(), 0);
            ctx.y -= 8;

            // ── All Transactions ──
            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع المعاملات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawAllTxTable(ctx, transactions);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير المعاملات إلى PDF", e);
        }
    }

    public void exportProductsPdf(Path path) {
        List<ProductRow> products = service.products("");

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "قائمة المنتجات";
            ctx.reportType  = "تقرير منتجات";

            newPage(ctx);

            // ── Summary ──
            ctx.y = drawProductsSummary(ctx, products);
            ctx.y -= 8;

            // ── Products Table ──
            if (!products.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع المنتجات النشطة", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, products);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير قائمة المنتجات إلى PDF", e);
        }
    }

    public void exportMonthlyPdf(Path path, int year, int month) {
        List<TransactionRow> allTx = service.getTransactionsForMonth(year, month);
        DailyCardRow card = service.getDailyCardForMonth(year, month);
        List<ProductRow> stock = service.products("");
        List<CashLedgerRow> cashOps = service.getCashLedgerForMonth(year, month);
        List<CustomerTransaction> customerTx = service.getCustomerTransactionsForMonth(year, month);
        List<InventoryService.SupplierDailyRecord> supplierTx = service.getSupplierTransactionsForMonth(year, month);

        List<TransactionRow> sales = allTx.stream()
                .filter(t -> "بيع".equals(t.type())).toList();
        List<TransactionRow> purchases = allTx.stream()
                .filter(t -> "شراء".equals(t.type())).toList();
        List<TransactionRow> returnsList = allTx.stream()
                .filter(t -> "مرتجع".equals(t.type())).toList();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            LocalDate monthStart = LocalDate.of(year, month, 1);
            ctx.reportDate = monthStart;
            ctx.reportEndDate = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
            ctx.reportTitle = "التقرير الشهري";
            ctx.reportType  = "تقرير شهري";

            newPage(ctx);

            ctx.y = drawSummarySection(ctx, card, allTx.size(), 0);
            ctx.y -= 8;

            // ── Sales Table ──
            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, " المبيعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, sales);
                ctx.y -= 10;
            }

            // ── Purchases Table ──
            if (!purchases.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المشتريات / الوارد", ctx.y);
                ctx.y -= 4;
                ctx.y = drawPurchasesTable(ctx, purchases);
                ctx.y -= 10;
            }

            // ── Returns Table ──
            if (!returnsList.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المرتجعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, returnsList);
                ctx.y -= 10;
            }

            // ── Current Stock Table ──
            if (!stock.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المنتجات الموجودة في المخزن", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, stock);
                ctx.y -= 10;
            }

            // ── Cash Ledger Table ──
            if (!cashOps.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات الخزينة (السحب والإيداع)", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCashTable(ctx, cashOps);
                ctx.y -= 10;
            }

            // ── All Transactions Table ──
            if (!allTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع معاملات الشهر", ctx.y);
                ctx.y -= 4;
                ctx.y = drawAllTxTable(ctx, allTx);
                ctx.y -= 10;
            }

            // ── Customer Transactions Table ──
            if (!customerTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات العملاء", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCustomerTxTable(ctx, customerTx);
                ctx.y -= 10;
            }

            // ── Supplier Transactions Table ──
            if (!supplierTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات الموردين", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSupplierTxTable(ctx, supplierTx);
                ctx.y -= 10;
            }

            // ── Tool Rentals Table (monthly) ──
            java.util.List<InventoryService.RentalRecord> monthRentals = service.getAllRentals().stream()
                .filter(r -> r.date() != null && r.date().length() >= 7)
                .filter(r -> {
                    try {
                        String ym = r.date().substring(0, 7);
                        String target = String.format("%04d-%02d", year, month);
                        return ym.equals(target);
                    } catch (Exception ignored) { return false; }
                })
                .toList();
            if (!monthRentals.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات تأجير العدد", ctx.y);
                ctx.y -= 4;
                String[] rentalHeaders = {"التاريخ", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الربح"};
                String[][] rentalData = new String[monthRentals.size()][];
                for (int i = 0; i < monthRentals.size(); i++) {
                    InventoryService.RentalRecord r = monthRentals.get(i);
                    rentalData[i] = new String[]{
                        r.date(),
                        r.toolCode() != null && !r.toolCode().isEmpty() ? r.toolCode() : "—",
                        r.toolName(),
                        r.size() != null && !r.size().isEmpty() ? r.size() : "—",
                        r.renterName() != null && !r.renterName().isEmpty() ? r.renterName() : "—",
                        moneyClean(r.price()),
                        moneyClean(r.price())
                    };
                }
                ctx.y = drawTable(ctx, rentalHeaders, null, java.util.Arrays.asList(rentalData));
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير التقرير الشهري إلى PDF", e);
        }
    }

    public void exportLowStockPdf(Path path) {
        List<ProductRow> products = service.products("");
        List<ProductRow> lowStock = products.stream()
                .filter(p -> p.quantity() <= 5)
                .toList();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير المنتجات منخفضة الكمية";
            ctx.reportType  = "تقرير مخزون";

            newPage(ctx);

            // ── Simple info ──
            ctx.y = drawSimpleInfo(ctx, "عدد المنتجات منخفضة الكمية: " + lowStock.size());
            ctx.y -= 8;

            if (!lowStock.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المنتجات منخفضة الكمية (5 أو أقل)", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, lowStock);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير المخزون إلى PDF", e);
        }
    }

    public void exportProfitPdf(Path path) {
        DailyCardRow card = service.getDailyCard(LocalDate.now());
        List<TransactionRow> transactions = service.getTransactionsForDate(LocalDate.now());

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الأرباح";
            ctx.reportType  = "تقرير أرباح";

            newPage(ctx);

            ctx.y = drawSummarySection(ctx, card, transactions.size(), 0);
            ctx.y -= 8;

            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "تفاصيل المعاملات والأرباح", ctx.y);
                ctx.y -= 4;
                ctx.y = drawAllTxTable(ctx, transactions);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الأرباح إلى PDF", e);
        }
    }

    public void exportPriceHistoryPdf(Path path) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير حركة الأسعار";
            ctx.reportType  = "تقرير أسعار";

            newPage(ctx);

            ctx.y = drawSimpleInfo(ctx, "تقرير شامل لحركة تغير أسعار المنتجات");
            ctx.y -= 8;

            ctx.y = ensureSpace(ctx, ctx.y, 60);
            ctx.y = drawSectionTitle(ctx, "حركة الأسعار", ctx.y);

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الأسعار إلى PDF", e);
        }
    }

    public void exportCompaniesPdf(Path path) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الشركات";
            ctx.reportType  = "تقرير شركات";

            newPage(ctx);

            ctx.y = drawSimpleInfo(ctx, "قائمة جميع الشركات الموردة");
            ctx.y -= 8;

            ctx.y = ensureSpace(ctx, ctx.y, 60);
            ctx.y = drawSectionTitle(ctx, "قائمة الشركات", ctx.y);

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الشركات إلى PDF", e);
        }
    }

    public void exportCategoriesPdf(Path path) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الأقسام";
            ctx.reportType  = "تقرير أقسام";

            newPage(ctx);

            ctx.y = drawSimpleInfo(ctx, "قائمة جميع أقسام المنتجات");
            ctx.y -= 8;

            ctx.y = ensureSpace(ctx, ctx.y, 60);
            ctx.y = drawSectionTitle(ctx, "قائمة الأقسام", ctx.y);

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الأقسام إلى PDF", e);
        }
    }

    public void exportBalancePdf(Path path) {
        DailyCardRow card = service.getDailyCard(LocalDate.now());

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الرصيد";
            ctx.reportType  = "تقرير رصيد";

            newPage(ctx);

            ctx.y = drawSummarySection(ctx, card, 0, 0);

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الرصيد إلى PDF", e);
        }
    }

    public void exportInvoicesPdf(Path path) {
        List<TransactionRow> transactions = service.getTransactionsForDate(LocalDate.now());
        List<TransactionRow> sales = transactions.stream()
                .filter(t -> "بيع".equals(t.type())).toList();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الفواتير";
            ctx.reportType  = "تقرير فواتير";

            newPage(ctx);

            ctx.y = drawSimpleInfo(ctx, "عدد فواتير البيع: " + sales.size());
            ctx.y -= 8;

            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "فواتير البيع", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, sales);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الفواتير إلى PDF", e);
        }
    }

    public void exportAuditPdf(Path path) {
        List<ProductRow> products = service.products("");
        double totalValue = products.stream().mapToDouble(p -> p.sellingPrice() * p.quantity()).sum();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now();
            ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الجرد";
            ctx.reportType  = "تقرير جرد";

            newPage(ctx);

            ctx.y = drawSimpleInfo(ctx, "إجمالي قيمة المخزون: " + moneyClean(totalValue) + "  |  عدد المنتجات: " + products.size());
            ctx.y -= 8;

            if (!products.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "بيانات الجرد", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, products);
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير تقرير الجرد إلى PDF", e);
        }
    }

    public void exportYearlyPdf(Path path, int year) {
        List<TransactionRow> allTx = service.getTransactionsForYear(year);
        DailyCardRow card = service.getDailyCardForYear(year);
        List<ProductRow> stock = service.products("");
        List<CashLedgerRow> cashOps = service.getCashLedgerForYear(year);
        List<CustomerTransaction> customerTx = service.getCustomerTransactionsForYear(year);
        List<InventoryService.SupplierDailyRecord> supplierTx = service.getSupplierTransactionsForYear(year);

        List<TransactionRow> sales = allTx.stream()
                .filter(t -> "بيع".equals(t.type())).toList();
        List<TransactionRow> purchases = allTx.stream()
                .filter(t -> "شراء".equals(t.type())).toList();
        List<TransactionRow> returnsList = allTx.stream()
                .filter(t -> "مرتجع".equals(t.type())).toList();

        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx();
            ctx.doc = doc;
            ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.of(year, 1, 1);
            ctx.reportEndDate = LocalDate.of(year, 12, 31);
            ctx.reportTitle = "التقرير السنوي";
            ctx.reportType  = "تقرير سنوي";

            newPage(ctx);

            ctx.y = drawSummarySection(ctx, card, allTx.size(), 0);
            ctx.y -= 8;

            // ── Sales Table ──
            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, " المبيعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, sales);
                ctx.y -= 10;
            }

            // ── Purchases Table ──
            if (!purchases.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المشتريات / الوارد", ctx.y);
                ctx.y -= 4;
                ctx.y = drawPurchasesTable(ctx, purchases);
                ctx.y -= 10;
            }

            // ── Returns Table ──
            if (!returnsList.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المرتجعات", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, returnsList);
                ctx.y -= 10;
            }

            // ── Current Stock Table ──
            if (!stock.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "المنتجات الموجودة في المخزن", ctx.y);
                ctx.y -= 4;
                ctx.y = drawStockTable(ctx, stock);
                ctx.y -= 10;
            }

            // ── Cash Ledger Table ──
            if (!cashOps.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات الخزينة (السحب والإيداع)", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCashTable(ctx, cashOps);
                ctx.y -= 10;
            }

            // ── All Transactions Table ──
            if (!allTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع معاملات السنة", ctx.y);
                ctx.y -= 4;
                ctx.y = drawAllTxTable(ctx, allTx);
                ctx.y -= 10;
            }

            // ── Customer Transactions Table ──
            if (!customerTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات العملاء", ctx.y);
                ctx.y -= 4;
                ctx.y = drawCustomerTxTable(ctx, customerTx);
                ctx.y -= 10;
            }

            // ── Supplier Transactions Table ──
            if (!supplierTx.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "معاملات الموردين", ctx.y);
                ctx.y -= 4;
                ctx.y = drawSupplierTxTable(ctx, supplierTx);
                ctx.y -= 10;
            }

            // ── Tool Rentals Table (yearly) ──
            java.util.List<InventoryService.RentalRecord> yearRentals = service.getAllRentals().stream()
                .filter(r -> r.date() != null && r.date().length() >= 4)
                .filter(r -> {
                    try {
                        String yr = r.date().substring(0, 4);
                        return yr.equals(String.valueOf(year));
                    } catch (Exception ignored) { return false; }
                })
                .toList();
            if (!yearRentals.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات تأجير العدد", ctx.y);
                ctx.y -= 4;
                String[] rentalHeaders = {"التاريخ", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الربح"};
                String[][] rentalData = new String[yearRentals.size()][];
                for (int i = 0; i < yearRentals.size(); i++) {
                    InventoryService.RentalRecord r = yearRentals.get(i);
                    rentalData[i] = new String[]{
                        r.date(),
                        r.toolCode() != null && !r.toolCode().isEmpty() ? r.toolCode() : "—",
                        r.toolName(),
                        r.size() != null && !r.size().isEmpty() ? r.size() : "—",
                        r.renterName() != null && !r.renterName().isEmpty() ? r.renterName() : "—",
                        moneyClean(r.price()),
                        moneyClean(r.price())
                    };
                }
                ctx.y = drawTable(ctx, rentalHeaders, null, java.util.Arrays.asList(rentalData));
            }

            closeAndAddFooters(ctx);
            doc.save(path.toFile());
        } catch (IOException e) {
            throw new RuntimeException("فشل تصدير التقرير السنوي إلى PDF", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PAGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    private void newPage(PdfCtx ctx) throws IOException {
        if (ctx.cs != null) ctx.cs.close();
        PDPage page = new PDPage(PDRectangle.A4);
        ctx.doc.addPage(page);
        ctx.pages.add(page);
        ctx.cs = new PDPageContentStream(ctx.doc, page);
        drawPageFrame(ctx);
        ctx.y = drawPageHeader(ctx);
    }

    private void drawPageFrame(PdfCtx ctx) throws IOException {
        PDPageContentStream cs = ctx.cs;
        // Frame coordinates: encompass entire page content area
        float frameX = MARGIN - 6;
        float frameBottom = 12;
        float frameTop = PAGE_H - 12;
        float frameW = CONTENT_W + 12;
        float frameH = frameTop - frameBottom;

        // ── Outermost frame (dark navy) ──
        cs.setNonStrokingColor(C_PRIMARY_DARK);
        cs.setLineWidth(2.5f);
        cs.addRect(frameX - 6, frameBottom - 6, frameW + 12, frameH + 12);
        cs.stroke();

        // ── Outer frame (navy) ──
        cs.setNonStrokingColor(C_FRAME);
        cs.setLineWidth(1.5f);
        cs.addRect(frameX - 2, frameBottom - 2, frameW + 4, frameH + 4);
        cs.stroke();

        // ── Inner frame (gold accent) ──
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.6f);
        cs.addRect(frameX + 2, frameBottom + 2, frameW - 4, frameH - 4);
        cs.stroke();

        cs.setLineWidth(1f);
    }

    private void closeAndAddFooters(PdfCtx ctx) throws IOException {
        if (ctx.cs != null) ctx.cs.close();
        addFooters(ctx);
    }

    private float ensureSpace(PdfCtx ctx, float currentY, float needed) throws IOException {
        if (currentY - needed < BOTTOM_Y) {
            newPage(ctx);
            return ctx.y;
        }
        return currentY;
    }

    // ═══════════════════════════════════════════════════════════════
    // PAGE HEADER — Corporate Minimal: Shop name, system, title, date/time
    // ═══════════════════════════════════════════════════════════════

    private float drawPageHeader(PdfCtx ctx) throws IOException {
        PDPageContentStream cs = ctx.cs;
        float y = PAGE_H - MARGIN;

        // ── Professional frame top border ──
        cs.setNonStrokingColor(C_FRAME);
        cs.setLineWidth(1.5f);
        cs.addRect(MARGIN - 4, y + 2, CONTENT_W + 8, 3f);
        cs.fill();
        y -= 10;

        // ── Gold accent line ──
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.8f);
        cs.addRect(MARGIN + 10, y - 5, CONTENT_W - 20, 1f);
        cs.fill();
        y -= 16;

        // ── Shop name (centered) with decorative frame ──
        float titleFontSize = 18;
        float titleY = y + 25;
        float titleTextW = measureText(ctx, ctx.shopName, titleFontSize);
        float framePadX = 30;
        float framePadY = 8;
        float frameX = (PAGE_W - titleTextW) / 2f - framePadX;
        float frameW = titleTextW + 2 * framePadX;
        float frameH = titleFontSize + 2 * framePadY;
        float frameY = titleY - framePadY;

        // Title frame background
        cs.setNonStrokingColor(new Color(240, 244, 250));
        cs.addRect(frameX, frameY, frameW, frameH);
        cs.fill();

        // Title frame outer border (navy)
        cs.setNonStrokingColor(C_FRAME);
        cs.setLineWidth(1.2f);
        cs.addRect(frameX, frameY, frameW, frameH);
        cs.stroke();

        // Title frame inner border (gold)
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.5f);
        cs.addRect(frameX + 3, frameY + 3, frameW - 6, frameH - 6);
        cs.stroke();

        // Title text
        centerText(ctx, ctx.shopName, titleY, titleFontSize, C_PRIMARY);
        y -= 22;

        // ── Report title (right-aligned, in decorative frame) ──
        float titleFontSize2 = 14;
        float titleY2 = y - 5;
        float titleTextW2 = measureText(ctx, ctx.reportTitle, titleFontSize2);
        float tFramePadX = 18;
        float tFramePadY = 6;
        float titleOffsetX = 5; // مقدار التحريك لليسار
        float tFrameW = titleTextW2 + 2 * tFramePadX;
        float tFrameX = PAGE_W - MARGIN - tFrameW - titleOffsetX;
        float tFrameH = titleFontSize2 + 2 * tFramePadY;
        float tFrameY = titleY2 - tFramePadY + 2;

        // Title frame background
        cs.setNonStrokingColor(new Color(240, 244, 250));
        cs.addRect(tFrameX, tFrameY, tFrameW, tFrameH);
        cs.fill();

        // Title frame outer border (navy)
        cs.setNonStrokingColor(C_FRAME);
        cs.setLineWidth(1.0f);
        cs.addRect(tFrameX, tFrameY, tFrameW, tFrameH);
        cs.stroke();

        // Title frame inner border (gold)
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.4f);
        cs.addRect(tFrameX + 3, tFrameY + 3, tFrameW - 6, tFrameH - 6);
        cs.stroke();

        // Title text
        textAt(
            ctx,
            ctx.reportTitle,
            tFrameX + tFrameW - tFramePadX,
            y - 3,
            titleFontSize2,
            C_PRIMARY_DARK
            );
        y -= 22;

        // ── Date / Day / Print Date line (single row, 3 segments) ──
        String dayName = ctx.reportDate.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("ar"));
        String dateOnly = formatDateArabicDateOnly(ctx.reportDate);
        String printDateOnly = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // Right segment: التاريخ
        String seg1 = "التاريخ : " + dateOnly;
        String seg2 = "يوم : " + dayName;
        String seg3 = "تاريخ الطباعة : " + printDateOnly;
        float segFontSize = 8.5f;
        float segY = y;
        // Draw right-aligned: seg1 at right, seg2 at center, seg3 at left
        textAt(ctx, seg1, PAGE_W - MARGIN, segY, segFontSize, C_ACCENT);
        float seg1W = measureText(ctx, seg1, segFontSize);
        float seg2W = measureText(ctx, seg2, segFontSize);
        // Center seg2 (no pre-shape — centeredText handles it)
        centeredText(ctx.cs, ctx.font, seg2, PAGE_W / 2, segY, segFontSize, C_ACCENT);
        // Left-aligned seg3
        leftText(ctx.cs, ctx.font, seg3, MARGIN, segY, segFontSize, C_ACCENT);
        y -= 14;

        // ── Period info (if applicable) ──
        if (ctx.reportEndDate != null && !ctx.reportDate.equals(ctx.reportEndDate)) {
            String periodStr = "الفترة: " + formatDateArabic(ctx.reportDate) + " — " + formatDateArabic(ctx.reportEndDate);
            textAt(ctx, periodStr, PAGE_W - MARGIN, y, 7.5f, C_TEXT_SEC);
            y -= 12;
        }

        // ── Gold accent line ──
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.8f);
        cs.addRect(MARGIN + 10, y, CONTENT_W - 20, 1f);
        cs.fill();
        y -= 6;

        return y;
    }

    // ═══════════════════════════════════════════════════════════════
    // PAGE FOOTER — Simple: page number + system name + print date
    // ═══════════════════════════════════════════════════════════════

    private void addFooters(PdfCtx ctx) throws IOException {
        int total = ctx.pages.size();
        String printTimestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String printDateArabic = formatReportDateArabic(ctx.reportDate);

        for (int i = 0; i < total; i++) {
            PDPage page = ctx.pages.get(i);
            try (PDPageContentStream fcs = new PDPageContentStream(
                    ctx.doc, page, PDPageContentStream.AppendMode.APPEND, true)) {

                // ── Gold accent line above footer ──
                fcs.setNonStrokingColor(C_GOLD);
                fcs.setLineWidth(0.8f);
                fcs.addRect(MARGIN + 10, BOTTOM_Y + 6, CONTENT_W - 20, 0.8f);
                fcs.fill();

                // ── Navy separator line ──
                fcs.setNonStrokingColor(C_FRAME);
                fcs.setLineWidth(1.5f);
                fcs.addRect(MARGIN, BOTTOM_Y, CONTENT_W, 1.5f);
                fcs.fill();


                // ── Page number (center) ──
                String pageText = "صفحة " + (i + 1) + " من " + total;
                centeredText(fcs, ctx.font, pageText, PAGE_W / 2, BOTTOM_Y - 16, 8f, C_PRIMARY_DARK);

                // ── Print date (left) ──
                leftText(fcs, ctx.font, printDateArabic,
                        MARGIN, BOTTOM_Y - 16, 6.5f, C_TEXT_SEC);

                // ── Bottom accent line ──
                fcs.setNonStrokingColor(C_FRAME);
                fcs.setLineWidth(1.5f);
                fcs.addRect(MARGIN - 4, BOTTOM_Y - FOOTER_H + 10, CONTENT_W + 8, 2f);
                fcs.fill();
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY SECTIONS — Clean corporate info tables
    // ═══════════════════════════════════════════════════════════════

    private float drawSummarySection(PdfCtx ctx, DailyCardRow card, int txCount, int productCount) throws IOException {
        float y = ctx.y;
        y = drawSectionTitle(ctx, "ملخص التقرير", y);
        y -= 4;

        // ── 2-column info table ──
        float colW = CONTENT_W / 2f;
        float rowH = 20;
        String[][] info = {
            { ctx.reportType,"نوع التقرير"},
            { formatDate(ctx.reportDate),"تاريخ الإنشاء"},
            { String.valueOf(txCount),"عدد المعاملات"},
            { moneyClean(card.sales()),"إجمالي المبيعات"},
            { moneyClean(card.profit()),"إجمالي الأرباح"},
            { moneyClean(card.wholesale()),"إجمالي المشتريات"},
            { moneyClean(card.balance()),"الرصيد الحالي"},
            { moneyClean(card.deposit()),"الإيداعات"},
            { moneyClean(card.withdrawal()),"المسحوبات"}
        };
        if (productCount > 0) {
            String[][] extended = new String[info.length + 1][2];
            System.arraycopy(info, 0, extended, 0, info.length);
            extended[info.length] = new String[]{"عدد المنتجات", String.valueOf(productCount)};
            info = extended;
        }

        int rows = (info.length + 1) / 2;
        y = ensureSpace(ctx, y, rows * rowH + 10);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                if (idx >= info.length) break;

                float x = (col == 0) ? PAGE_W - MARGIN - colW : PAGE_W - MARGIN - colW * 2;

                // ── Label ──
                cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                drawCellLabel(ctx, info[idx][0], x + 6, y - rowH / 2 - 3, 7);

                // ── Value ──
                drawCellValue(ctx, info[idx][1], x + colW - 6, y - rowH / 2 - 3, 7.5f);

                // ── Cell border ──
                cs(ctx).setNonStrokingColor(C_BORDER);
                cs(ctx).setLineWidth(0.3f);
                cs(ctx).addRect(x, y - rowH, colW, rowH);
                cs(ctx).stroke();
            }
            y -= rowH;
        }

        return y;
    }

    private float drawProductsSummary(PdfCtx ctx, List<ProductRow> products) throws IOException {
        float y = ctx.y;
        y = drawSectionTitle(ctx, "ملخص التقرير", y);
        y -= 4;

        long activeCount = products.size();
        double totalValue = products.stream().mapToDouble(p -> p.sellingPrice() * p.quantity()).sum();
        long weightProducts = products.stream().filter(p -> "WEIGHT".equals(p.unit())).count();
        long packageProducts = products.stream().filter(p -> "PACKAGE".equals(p.unit())).count();
        long pieceProducts = products.stream().filter(p -> "PIECE".equals(p.unit())).count();

        String[][] info = {
            { ctx.reportType, "نوع التقرير" },
            {formatDate(ctx.reportDate), "تاريخ الإنشاء"},
            {String.valueOf(activeCount),"عدد المنتجات"},
            {moneyClean(totalValue),"إجمالي القيمة"},
            {String.valueOf(weightProducts),"المنتجات بالوزن"},
            {String.valueOf(packageProducts),"المنتجات بالحزمة"},
            {String.valueOf(pieceProducts),"المنتجات بالقطعة"}
        };

        float colW = CONTENT_W / 2f;
        float rowH = 20;
        int rows = (info.length + 1) / 2;
        y = ensureSpace(ctx, y, rows * rowH + 10);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                if (idx >= info.length) break;

                float x = (col == 0) ? PAGE_W - MARGIN - colW : PAGE_W - MARGIN - colW * 2;

                cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                drawCellLabel(ctx, info[idx][0], x + 6, y - rowH / 2 - 3, 7);

                drawCellValue(ctx, info[idx][1], x + colW - 6, y - rowH / 2 - 3, 7.5f);

                cs(ctx).setNonStrokingColor(C_BORDER);
                cs(ctx).setLineWidth(0.3f);
                cs(ctx).addRect(x, y - rowH, colW, rowH);
                cs(ctx).stroke();
            }
            y -= rowH;
        }

        return y;
    }

    private float drawSimpleInfo(PdfCtx ctx, String text) throws IOException {
        float y = ctx.y;
        y = ensureSpace(ctx, y, 30);
        textAt(ctx, text, PAGE_W - MARGIN, y - 14, 9, C_TEXT);
        y -= 24;
        return y;
    }

    // ═══════════════════════════════════════════════════════════════
    // SECTION TITLE — Clean text-only, no count badges
    // ═══════════════════════════════════════════════════════════════

    private float drawSectionTitle(PdfCtx ctx, String title, float startY) throws IOException {
        startY = ensureSpace(ctx, startY, 35);
        float yCur = startY;

        PDPageContentStream cs = ctx.cs;

        // ── Section title background ──
        cs.setNonStrokingColor(new Color(240, 244, 250));
        cs.addRect(MARGIN, yCur - 24, CONTENT_W, 22);
        cs.fill();

        // ── Thin accent bar (right side, navy) ──
        cs.setNonStrokingColor(C_PRIMARY);
        cs.addRect(PAGE_W - MARGIN - 2, yCur - 22, 4, 18);
        cs.fill();

        // ── Gold accent dot ──
        cs.setNonStrokingColor(C_GOLD);
        cs.addRect(PAGE_W - MARGIN - 2, yCur - 8, 4, 2);
        cs.fill();

        // ── Title text ──
        textAt(ctx, title, PAGE_W - MARGIN - 10, yCur - 17, 12, C_PRIMARY_DARK);

        // ── Bottom separator line (gold) ──
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(0.6f);
        cs.addRect(MARGIN, yCur - 26, CONTENT_W, 0.8f);
        cs.fill();

        cs.setLineWidth(1f);
        return yCur - 32;
    }

    // ═══════════════════════════════════════════════════════════════
    // TABLES — Clean minimal corporate style
    // ═══════════════════════════════════════════════════════════════

    private float drawSalesTable(PdfCtx ctx, List<TransactionRow> rows) throws IOException {
        // Matching UI sales data format: الوقت, المنتج, الكمية, السعر, الإجمالي, الربح
        String[] headers = {"الوقت", "المنتج", "الكمية / السعة", "السعر", "الإجمالي", "الربح"};
        float[] widths = {62, 150, 58, 58, 78, 78};

        List<String[]> data = new ArrayList<>();
        for (TransactionRow tr : rows) {
            double pw = tr.packageWeight();
            String mu = tr.measurementUnit() != null ? tr.measurementUnit().trim() : "";
            String ut = tr.unitType() != null ? tr.unitType() : "COUNT";
            String capDisplay = "—";
            if ("PACKAGE".equals(ut) && pw > 0) {
                if (!mu.isEmpty()) {
                    capDisplay = switch (mu) {
                        case "لتر" -> String.format("%,.2f لتر", pw);
                        case "ميللي لتر" -> String.format("%,.0f ميللي لتر", pw);
                        default -> String.format("%,.2f %s", pw, mu);
                    };
                } else {
                    capDisplay = String.format("%,.2f", pw);
                }
            }
            data.add(new String[]{
                tr.time(),
                tr.productName() != null ? tr.productName() : "-",
                capDisplay,
                moneyClean(tr.price()),
                moneyClean(tr.total()),
                moneyClean(tr.profit())
            });
        }
        return drawTable(ctx, headers, widths, data);
    }

    private float drawPurchasesTable(PdfCtx ctx, List<TransactionRow> rows) throws IOException {
        // Matching UI: الوقت, المنتج, الكمية / السعة, العدد, السعر, الإجمالي
        String[] headers = {"الوقت", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي"};
        float[] widths = {70, 160, 68, 42, 72, 92};

        List<String[]> data = new ArrayList<>();
        for (TransactionRow tr : rows) {
            double qty = tr.quantity();
            double pw = tr.packageWeight();
            String mu = tr.measurementUnit() != null ? tr.measurementUnit().trim() : "";
            String ut = tr.unitType() != null ? tr.unitType() : "COUNT";
            // الكمية / السعة = سعة الوحدة (packageWeight) من شاشة إضافة الوارد
            String capDisplay = "—";
            if ("PACKAGE".equals(ut) && pw > 0) {
                if (!mu.isEmpty()) {
                    capDisplay = switch (mu) {
                        case "لتر" -> String.format("%,.2f لتر", pw);
                        case "ميللي لتر" -> String.format("%,.0f ميللي لتر", pw);
                        default -> String.format("%,.2f %s", pw, mu);
                    };
                } else {
                    capDisplay = String.format("%,.2f", pw);
                }
            }
            // العدد — for COUNT/PACKAGE products, show quantity; otherwise "—"
            String countDisplay = "—";
            if ("COUNT".equals(ut) && "عدد".equals(mu) && qty > 0) { countDisplay = String.format("%,.0f", qty); }
            else if ("PACKAGE".equals(ut) && qty > 0) { countDisplay = String.format("%,.0f", qty); }

            data.add(new String[]{
                tr.time(),
                tr.productName() != null ? tr.productName() : "-",
                capDisplay,
                countDisplay,
                moneyClean(tr.price()),
                moneyClean(tr.total())
            });
        }
        return drawTable(ctx, headers, widths, data);
    }

private float drawStockTable(PdfCtx ctx, List<ProductRow> products) throws IOException {
    // Matching UI: تاريخ الإضافة, الكود, الاسم, الشركة, الصنف, سعر البيع,
    // سعر الجملة, الكمية, العدد, الوحدة, كود المورد, اسم المورد, هاتف المورد, الربح
    String[] headers = {
            "تاريخ الإضافة",
            "الكود",
            "الاسم",
            "الشركة",
            "الصنف",
            "سعر البيع",
            "سعر الجملة",
            "الكمية / السعة",
            "العدد",
            "الوحدة",
            "كود المورد",
            "اسم المورد",
            
            "الربح"
    };
    float[] widths = {
            40, // تاريخ الإضافة
            33, // الكود
            45, // الاسم
            33, // الشركة
            33, // الصنف
            33, // سعر البيع
            33, // سعر الجملة
            35, // الكمية
            10, // العدد
            33, // الوحدة
            30, // كود المورد
            40, // اسم المورد
             // هاتف المورد
            33  // الربح
    };

    float maxTableWidth = ctx.getContentWidth();
    float totalWidth = 0f;
    for (float w : widths) totalWidth += w;

    if (totalWidth > maxTableWidth) {
        float scale = maxTableWidth / totalWidth;
        for (int i = 0; i < widths.length; i++) {
            widths[i] = widths[i] * scale;
        }
    }

    List<String[]> data = new ArrayList<>();
    for (ProductRow p : products) {
        String createdAt = "";
        if (p.createdAt() != null && !p.createdAt().isEmpty()) {
            createdAt = p.createdAt().length() >= 10
                    ? p.createdAt().substring(0, 10)
                    : p.createdAt();
        }
        String countVal = "";
        boolean isCountProd =
                "COUNT".equals(p.unit())
                && "عدد".equals(p.measurementUnit());
        if (!isCountProd) {
            if ("PACKAGE".equals(p.unit()) && p.packageWeight() > 0) {
                countVal = String.format("%,.0f", p.quantity());
            }
        } else {
            if (p.unitCount() > 0) {
                countVal = String.format("%,.0f", p.unitCount());
            }
        }
        String supplierCode =
                p.supplierCode() != null
                && !p.supplierCode().isEmpty()
                ? p.supplierCode()
                : "";
        String supplierName =
                p.supplierName() != null
                && !p.supplierName().isEmpty()
                ? p.supplierName()
                : "";
        String supplierPhone =
                p.supplierPhone() != null
                && !p.supplierPhone().isEmpty()
                ? p.supplierPhone()
                : "";
        String unitName =
                p.unitName() != null
                && !p.unitName().isEmpty()
                ? p.unitName()
                : unitLabel(p.unit());
        double profit = Math.max(0, p.sellingPrice() - p.wholesalePrice());
        // الكمية / السعة = سعة الوحدة (packageWeight) — من شاشة إضافة الوارد
        String capDisplay = "—";
        double pw = p.packageWeight();
        String mu = p.measurementUnit() != null ? p.measurementUnit().trim() : "";
        String ut = p.unit() != null ? p.unit() : "COUNT";
        if ("PACKAGE".equals(ut) && pw > 0) {
            if (!mu.isEmpty()) {
                capDisplay = switch (mu) {
                    case "لتر" -> String.format("%,.2f لتر", pw);
                    case "ميللي لتر" -> String.format("%,.0f ميللي لتر", pw);
                    default -> String.format("%,.2f %s", pw, mu);
                };
            } else {
                capDisplay = String.format("%,.2f", pw);
            }
        }
        data.add(new String[]{
                createdAt,
                p.code() != null ? p.code() : "",
                p.name() != null ? p.name() : "",
                p.company() != null ? p.company() : "",
                p.category() != null ? p.category() : "",
                moneyClean(p.sellingPrice()),
                moneyClean(p.wholesalePrice()),
                capDisplay,
                countVal,
                unitName,
                supplierCode,
                supplierName,
                
                moneyClean(profit)
        });
    }
    return drawTable(ctx, headers, widths, data);
}

    private float drawCashTable(PdfCtx ctx, List<CashLedgerRow> rows) throws IOException {
        String[] headers = {"الوقت", "النوع", "المبلغ", "المودع / الساحب", "ملاحظات"};
        float[] widths = {70, 70, 90, 110, 150};

        List<String[]> data = new ArrayList<>();
        for (CashLedgerRow row : rows) {
            String typeLabel = "DEPOSIT".equals(row.type()) ? "إيداع" : "سحب";
            String pn = row.personName() != null && !row.personName().isEmpty() ? row.personName() : "-";
            data.add(new String[]{row.time(), typeLabel, moneyClean(row.amount()), pn,
                row.notes() != null ? row.notes() : ""});
        }
        return drawTable(ctx, headers, widths, data);
    }

    private float drawAllTxTable(PdfCtx ctx, List<TransactionRow> rows) throws IOException {
        // Matching UI: الوقت, النوع, المنتج, الكمية, العدد, كود العميل, اسم العميل, كود المورد, اسم المورد, السعر, الربح, ملاحظات
        String[] headers = {"الوقت", "النوع", "المنتج", "الكمية / السعة", "العدد", "كود العميل", "اسم العميل", "كود المورد", "اسم المورد", "السعر", "الربح", "ملاحظات"};
        float[] widths = {45, 35, 90, 42, 30, 48, 60, 48, 60, 42, 42, 42};

        List<String[]> data = new ArrayList<>();
        for (TransactionRow tr : rows) {
            double qty = tr.quantity();
            double pw = tr.packageWeight();
            String mu = tr.measurementUnit() != null ? tr.measurementUnit().trim() : "";
            String ut = tr.unitType();
            // Format unit capacity (الكمية / السعة) — سعة الوحدة من شاشة إضافة الوارد
            String capDisplay = "—";
            if ("PACKAGE".equals(ut) && pw > 0) {
                if (!mu.isEmpty()) {
                    capDisplay = switch (mu) {
                        case "لتر" -> String.format("%,.2f لتر", pw);
                        case "ميللي لتر" -> String.format("%,.0f ميللي لتر", pw);
                        default -> String.format("%,.2f %s", pw, mu);
                    };
                } else {
                    capDisplay = String.format("%,.2f", pw);
                }
            }
            // Format count (العدد) matching UI — نفس المنطق القديم
            String countDisplay = "—";
            if ("COUNT".equals(ut) && "عدد".equals(mu) && qty > 0) { countDisplay = String.format("%,.0f", qty); }
            else if ("PACKAGE".equals(ut) && qty > 0) { countDisplay = String.format("%,.0f", qty); }

            String cc = tr.customerCode() != null && !tr.customerCode().isEmpty() ? tr.customerCode() : "";
            String cn = tr.customerName() != null && !tr.customerName().isEmpty() ? tr.customerName() : "";
            String sc = tr.supplierCode() != null && !tr.supplierCode().isEmpty() ? tr.supplierCode() : "";
            String sn = tr.supplierName() != null && !tr.supplierName().isEmpty() ? tr.supplierName() : "";
            data.add(new String[]{
                tr.time(),
                translateType(tr.type()),
                tr.productName() != null ? tr.productName() : "-",
                capDisplay,
                countDisplay,
                cc, cn, sc, sn,
                moneyClean(tr.price()),
                moneyClean(tr.profit()),
                tr.notes() != null ? tr.notes() : ""
            });
        }
        return drawTable(ctx, headers, widths, data);
    }

    private float drawCustomerTxTable(PdfCtx ctx, List<CustomerTransaction> rows) throws IOException {
        String[] headers = {"التاريخ", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "العميل", "رقم الهاتف", "ملاحظات"};
        float[] widths = {65, 38, 90, 42, 32, 48, 55, 65, 60, 38};

        List<String[]> data = new ArrayList<>();
        for (CustomerTransaction ct : rows) {
            String dt = ct.dateTime() != null && ct.dateTime().length() > 10 ? ct.dateTime().substring(0, 10) : (ct.dateTime() != null ? ct.dateTime() : "");
            String custName = ct.customerName() != null && !ct.customerName().isEmpty() ? ct.customerName() : "-";
            String phone = ct.phone() != null && !ct.phone().isEmpty() ? ct.phone() : "-";
            double qty = ct.quantity();
            // الكمية / السعة — CustomerTransaction has no packageWeight data, match UI which shows "—"
            String capDisplay = "—";
            // العدد — show quantity if it looks like whole units
            String countDisplay = (qty > 0 && qty == Math.rint(qty) && qty < 1e12)
                ? String.format("%,d", (long) qty)
                : "—";
            data.add(new String[]{dt, ct.type(), ct.productName(),
                capDisplay, countDisplay, moneyClean(ct.price()),
                moneyClean(ct.total()), custName, phone,
                ct.notes() != null ? ct.notes() : ""});
        }
        return drawTable(ctx, headers, widths, data);
    }

    private float drawSupplierTxTable(PdfCtx ctx, List<InventoryService.SupplierDailyRecord> rows) throws IOException {
        String[] headers = {"التاريخ", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "كود المورد", "اسم المورد", "رقم الهاتف", "ملاحظات"};
        float[] widths = {55, 35, 80, 38, 30, 42, 48, 48, 60, 50, 35};

        List<String[]> data = new ArrayList<>();
        for (InventoryService.SupplierDailyRecord sr : rows) {
            String sc = sr.supplierCode() != null && !sr.supplierCode().isEmpty() ? sr.supplierCode() : "-";
            String sn = sr.supplierName() != null && !sr.supplierName().isEmpty() ? sr.supplierName() : "-";
            String sp = sr.supplierPhone() != null && !sr.supplierPhone().isEmpty() ? sr.supplierPhone() : "-";
            double qty = sr.quantity();
            double pw = sr.packageWeight();
            String ut = sr.unitType() != null ? sr.unitType() : "COUNT";
            // الكمية / السعة = سعة الوحدة (packageWeight) المسجلة عند إضافة الوارد
            String capDisplay = "—";
            if (pw > 0) {
                capDisplay = String.format("%,.2f", pw);
            }
            // العدد — للمنتجات COUNT/PACKAGE فقط
            String countDisplay = (("COUNT".equals(ut) || "PACKAGE".equals(ut)) && qty > 0 && qty == Math.rint(qty) && qty < 1e12)
                ? String.format("%,d", (long) qty)
                : "—";
            data.add(new String[]{sr.time(), sr.type(), sr.productName(),
                capDisplay, countDisplay, moneyClean(sr.price()),
                moneyClean(sr.total()), sc, sn, sp,
                sr.notes() != null ? sr.notes() : ""});
        }
        return drawTable(ctx, headers, widths, data);
    }

    /**
     * حساب عرض الأعمدة ديناميكياً حسب المحتوى.
     * يقيس أطول نص في كل عمود (بما في ذلك العنوان) ويوزع المساحة المتاحة.
     * يضمن أن جميع الأعمدة لديها مساحة كافية لعرض المحتوى.
     */
    private float[] calculateColumnWidths(PdfCtx ctx, String[] headers, List<String[]> rows) {
        int n = headers.length;
        float[] widths = new float[n];
        float padding = 14;

        // قياس عرض كل عمود بناءً على أطول نص (باستخدام قياس الخط الفعلي)
        for (int i = 0; i < n; i++) {
            float maxW = measureText(ctx, headers[i], 7.5f);
            for (String[] row : rows) {
                String text = (i < row.length && row[i] != null) ? row[i] : "";
                float w = measureText(ctx, text, 6.5f);
                if (w > maxW) maxW = w;
            }
            widths[i] = maxW + padding;
        }

        // تطبيق حد أدنى وأقصى معقولين
        for (int i = 0; i < n; i++) {
            if (widths[i] < 40) widths[i] = 40;
            if (widths[i] > 160) widths[i] = 160;
        }

        // تطبيع المساحة الإجمالية لتتناسب مع CONTENT_W
        float total = 0;
        for (float w : widths) total += w;
        if (total > CONTENT_W) {
            // Scale proportionally with a reasonable minimum
            float scale = CONTENT_W / total;
            for (int i = 0; i < n; i++) widths[i] = Math.max(42, widths[i] * scale);

            // ── Second pass: guarantee no overflow ──
            // Math.max(42, ...) can cause total to still exceed CONTENT_W.
            // On this pass, proportionally reduce only the portion above 42pt.
            total = 0;
            for (float w : widths) total += w;
            if (total > CONTENT_W) {
                float excess = total - CONTENT_W;
                float totalFlex = 0;
                for (float w : widths) totalFlex += Math.max(0, w - 42);
                if (totalFlex > 0) {
                    float ratio = excess / totalFlex;
                    for (int i = 0; i < n; i++) {
                        float flex = Math.max(0, widths[i] - 42);
                        if (flex > 0) widths[i] -= flex * ratio;
                    }
                }
                // Extreme safety: if still too wide, flat scale everything
                total = 0;
                for (float w : widths) total += w;
                if (total > CONTENT_W) {
                    float s = CONTENT_W / total;
                    for (int i = 0; i < n; i++) widths[i] *= s;
                }
            }
        } else if (total < CONTENT_W) {
            // توزيع المساحة المتبقية بالتساوي
            float extra = (CONTENT_W - total) / n;
            for (int i = 0; i < n; i++) widths[i] += extra;
        }

        return widths;
    }

    /**
     * لف النص داخل الخلية — يقسم النص الطويل إلى أسطر متعددة تناسب عرض العمود.
     * يكسر النص فقط عند المسافات العادية للحفاظ على سلامة الكلمات العربية
     * والأرقام والتواريخ (لا يتم كسر الرقم أو العملة أو التاريخ في المنتصف).
     * إذا كانت كلمة واحدة أطول من عرض العمود، توضع كاملة في سطر منفصل.
     */
    private String wrapText(String text, float cellWidth, float charWidth) {
        if (text == null || text.isEmpty()) return text;
        int charsPerLine = Math.max(1, (int)(cellWidth / charWidth));
        if (text.length() <= charsPerLine) return text;
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < text.length()) {
            if (sb.length() > 0) sb.append("\n");
            if (pos + charsPerLine >= text.length()) {
                sb.append(text, pos, text.length());
                break;
            }
            int end = Math.min(pos + charsPerLine, text.length());
            // ابحث عن آخر مسافة ضمن النطاق
            int spaceAt = -1;
            for (int j = end; j > pos; j--) {
                if (text.charAt(j - 1) == ' ') {
                    spaceAt = j - 1;
                    break;
                }
            }
            if (spaceAt > pos) {
                sb.append(text, pos, spaceAt);
                pos = spaceAt + 1;
            } else {
                // لا توجد مسافة — ضع الكلمة كاملة (حتى المسافة التالية أو النهاية)
                int nextSpace = text.indexOf(' ', pos + charsPerLine);
                if (nextSpace > pos) {
                    sb.append(text, pos, nextSpace);
                    pos = nextSpace + 1;
                } else {
                    // باقي النص لا يحتوي على مسافات — اطبع الباقي كاملاً
                    sb.append(text, pos, text.length());
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * لف جميع الخلايا في جميع الصفوف.
     * يستخدم عرض حرف ديناميكي محسوب من قياس متوسط الأحرف العربية في الخط الحالي
     * لضمان دقة أعلى في التوزيع على الأسطر.
     */
    private String[][] wrapAllCells(PdfCtx ctx, String[] headers, float[] widths, List<String[]> rows) {
        int n = headers.length;
        // قياس متوسط عرض الحرف العربي في حجم الخط 7pt بدقة
        float cellCharWidth = 10.0f; // conservative estimate
        if (ctx != null && ctx.font != null) {
            try {
                // Use the actual font to measure average Arabic character width at 7pt
                String testStr = "م"; // single Arabic character
                String visual = shape(testStr);
                float charW = ctx.font.getStringWidth(visual) / 1000f * 7.5f;
                if (charW >= 5) cellCharWidth = Math.min(charW + 0.5f, 12.0f);
            } catch (Exception ignored) {}
        }
        List<String[]> wrappedRows = new ArrayList<>();
        for (String[] row : rows) {
            String[] wrapped = new String[n];
            for (int i = 0; i < n; i++) {
                String text = (i < row.length && row[i] != null) ? row[i] : "";
                wrapped[i] = wrapText(text, widths[i] - 6, cellCharWidth);
            }
            wrappedRows.add(wrapped);
        }
        return wrappedRows.toArray(new String[rows.size()][]);
    }

    /**
     * حساب ارتفاع الصف المطلوب بناءً على أطول خلية ملفوفة.
     */
    private int countLines(String text) {
        if (text == null || text.isEmpty()) return 1;
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * محرك الجداول الرئيسي — يدعم:
     * - عرض أعمدة ديناميكي حسب المحتوى
     * - بدون التفاف نص: أي نص أطول من عرض العمود يتم تصغير حجم الخط (Auto Font Scaling)
     * - دعم الأسطر المتعددة عبر \n المضمن في النص (للتاريخ والوقت)
     * - ارتفاع صفوف ديناميكي
     * - تلوين الصفوف بالتناوب
     * - كسر الصفحة تلقائياً مع تكرار العنوان
     */
    private float drawTable(PdfCtx ctx, String[] headers, float[] widthsIgnored, List<String[]> rows) throws IOException {
        // حساب العرض الديناميكي
        float[] widths = calculateColumnWidths(ctx, headers, rows);

        ctx.y = ensureSpace(ctx, ctx.y, 30);
        float yCur = ctx.y;

        // ── Table header ──
        yCur = drawTableHeader(ctx, headers, widths, yCur);

        // ── Table rows with Auto Font Scaling (no text wrapping) ──
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            // حساب عدد الأسطر المضمنة في كل خلية (عبر \n المضمن في النص فقط)
            int maxLines = 1;
            for (int c = 0; c < row.length && c < widths.length; c++) {
                String text = (c < row.length && row[c] != null) ? row[c] : "";
                int lines = countLines(text);
                if (lines > maxLines) maxLines = lines;
            }

            // حجم الخط التكيفي (Auto-Fit): اختيار الحجم المناسب حسب عدد الأسطر المضمنة
            float cellFontSize;
            if (maxLines <= 1) {
                cellFontSize = 7.0f;       // سطر واحد — حجم طبيعي
            } else if (maxLines == 2) {
                cellFontSize = 6.5f;       // سطرين (مثل التاريخ والوقت)
            } else {
                cellFontSize = 6.0f;       // 3+ أسطر
            }

            // ارتفاع الصف ديناميكي حسب عدد الأسطر
            float lineH = Math.max(15f, cellFontSize + 8f);
            float rowH = Math.max(LINE_H + 4, maxLines * lineH + 4);

            if (yCur - rowH - 2 < BOTTOM_Y) {
                newPage(ctx);
                yCur = ctx.y;
                yCur = drawTableHeader(ctx, headers, widths, yCur);
            }
            yCur = drawTableRow(ctx, row, widths, yCur, i % 2 == 1, rowH, cellFontSize);
        }

        // ── Bottom border ──
        ctx.cs.setNonStrokingColor(C_PRIMARY);
        ctx.cs.setLineWidth(0.8f);
        ctx.cs.addRect(MARGIN, yCur, CONTENT_W, 0);
        ctx.cs.stroke();
        ctx.cs.setLineWidth(1f);

        ctx.y = yCur - 5;
        return ctx.y;
    }

    private float drawTableHeader(PdfCtx ctx, String[] headers, float[] widths, float startY) throws IOException {
        PDPageContentStream cs = ctx.cs;
        float yCur = startY;
        // ارتفاع ثابت للعنوان — 28pt مناسب لسطر واحد
        float rowH = 32;

        // ── Header background ──
        cs.setNonStrokingColor(C_PRIMARY);
        cs.addRect(MARGIN, yCur - rowH, CONTENT_W, rowH);
        cs.fill();

        // ── Header border ──
        cs.setNonStrokingColor(C_PRIMARY_DARK);
        cs.setLineWidth(0.5f);
        cs.addRect(MARGIN, yCur - rowH, CONTENT_W, rowH);
        cs.stroke();

        // ── Bottom gold accent ──
        cs.setNonStrokingColor(C_GOLD);
        cs.setLineWidth(1f);
        cs.addRect(MARGIN, yCur - rowH, CONTENT_W, 0);
        cs.stroke();

        // ── Header cells — توسيط النص بدون لف ──
        float x = PAGE_W - MARGIN;
        for (int i = 0; i < headers.length && i < widths.length; i++) {
            x -= widths[i];
            String text = headers[i];
            // قياس عرض النص الفعلي لتقرير إذا كان يحتاج تصغير الخط
            float hdrFontSize = 7.5f;
            float availableW = widths[i] - 6;
            try {
                String visual = shape(text);
                float textW = ctx.font.getStringWidth(visual) / 1000f * hdrFontSize;
                if (textW > availableW && hdrFontSize > 5.5f) {
                    hdrFontSize = Math.max(5.5f, (availableW / textW) * hdrFontSize * 0.95f);
                }
            } catch (Exception ignored) {}
            // توسيط النص عمودياً وأفقياً داخل رأس العمود
            centeredText(cs, ctx.font, text, x + widths[i] / 2, yCur - rowH / 2 - 4, hdrFontSize, C_WHITE);
        }

        cs.setLineWidth(1f);
        return yCur - rowH;
    }

    private float drawTableRow(PdfCtx ctx, String[] cells, float[] widths, float startY, boolean alternate, float rowH, float cellFontSize) throws IOException {
        PDPageContentStream cs = ctx.cs;
        float yCur = startY;
        float lineH = Math.max(15f, cellFontSize + 8f);

        // ── Alternating row background ──
        if (alternate) {
            cs.setNonStrokingColor(C_ROW_ALT);
            cs.addRect(MARGIN, yCur - rowH, CONTENT_W, rowH);
            cs.fill();
        }

        // ── Bottom border ──
        cs.setNonStrokingColor(C_BORDER);
        cs.setLineWidth(0.25f);
        cs.addRect(MARGIN + 2, yCur - rowH, CONTENT_W - 4, 0);
        cs.stroke();

        // ── Vertical column separators ──
        cs.setNonStrokingColor(new Color(220, 225, 235));
        cs.setLineWidth(0.2f);
        float x = PAGE_W - MARGIN;
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            x -= widths[i];
            if (i > 0) {
                cs.addRect(x, yCur - rowH + 2, 0, rowH - 4);
                cs.stroke();
            }
        }

        // ── Cells with Auto Font Scaling (بدون Wrap) — توسيط أفقي وعمودي داخل الخلية ──
        x = PAGE_W - MARGIN;
        for (int i = 0; i < cells.length && i < widths.length; i++) {
            x -= widths[i];
            String text = cells[i] != null ? cells[i] : "-";
            String[] lines = text.split("\n", -1);
            float totalTextH = lines.length * lineH;
            float startY2 = yCur - (rowH - totalTextH) / 2f - lineH + 3;
            float availableW = Math.max(12, widths[i] - 5);

            for (int li = 0; li < lines.length; li++) {
                String line = lines[li];
                if (line == null || line.isEmpty()) continue;

                // Auto Font Scaling: قياس عرض النص وتقليل حجم الخط ليتناسب مع عرض العمود
                float lineFontSize = cellFontSize;
                try {
                    String visual = shape(line);
                    float textW = ctx.font.getStringWidth(visual) / 1000f * lineFontSize;
                    if (textW > availableW && lineFontSize > 4.5f) {
                        lineFontSize = Math.max(4.5f, (availableW / textW) * lineFontSize * 0.95f);
                    }
                    // محاولة ثانية: إذا لسه النص كبير جداً، نحاول تاني
                    float textW2 = ctx.font.getStringWidth(visual) / 1000f * lineFontSize;
                    if (textW2 > availableW && lineFontSize > 4.0f) {
                        lineFontSize = Math.max(4.0f, (availableW / textW2) * lineFontSize * 0.95f);
                    }
                } catch (Exception ignored) {}

                // رسم النص في منتصف الخلية (توسيط أفقي وعمودي)
                centeredText(cs, ctx.font, line, x + widths[i] / 2f, startY2 - li * lineH, lineFontSize, C_TEXT);
            }
        }

        cs.setLineWidth(1f);
        return yCur - rowH;
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY INFO CELL HELPERS
    // ═══════════════════════════════════════════════════════════════

    private void drawCellLabel(PdfCtx ctx, String text, float rightX, float y, float fontSize) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        PDPageContentStream cs = ctx.cs;
        cs.beginText();
        cs.setFont(ctx.font, fontSize);
        cs.setNonStrokingColor(C_TEXT_SEC);
        float tw = 0;
        try { tw = ctx.font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception ignored) {}
        cs.newLineAtOffset(rightX, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    private void drawCellValue(PdfCtx ctx, String text, float leftX, float y, float fontSize) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        PDPageContentStream cs = ctx.cs;
        cs.beginText();
        cs.setFont(ctx.font, fontSize);
        cs.setNonStrokingColor(C_TEXT);
        float tw = 0;
        try { tw = ctx.font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception ignored) {}
        cs.newLineAtOffset(leftX - tw, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    // ═══════════════════════════════════════════════════════════════
    // TEXT DRAWING — Arabic-aware with RTL support
    // ═══════════════════════════════════════════════════════════════
    private void centerText(PdfCtx ctx, String text, float y, float fontSize, Color color) throws IOException {
    String visual = shape(text);
    if (visual.isEmpty()) return;

    float tw = ctx.font.getStringWidth(visual) / 1000f * fontSize;
    float x = (PAGE_W - tw) / 2f;

    ctx.cs.beginText();
    ctx.cs.setFont(ctx.font, fontSize);
    ctx.cs.setNonStrokingColor(color);
    ctx.cs.newLineAtOffset(x, y);
    ctx.cs.showText(visual);
    ctx.cs.endText();
}

    /** Draw text right-aligned at (rightX, y). */
    private void textAt(PdfCtx ctx, String text, float rightX, float y, float fontSize, Color color) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        PDPageContentStream cs = ctx.cs;
        cs.beginText();
        cs.setFont(ctx.font, fontSize);
        cs.setNonStrokingColor(color);
        float tw = 0;
        try { tw = ctx.font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception ignored) {}
        cs.newLineAtOffset(rightX - tw, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    private static void centeredText(PDPageContentStream cs, PDType0Font font,
            String text, float centerX, float y, float fontSize, Color color) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(color);
        float tw = 0;
        try { tw = font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception ignored) {}
        cs.newLineAtOffset(centerX - tw / 2, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    private static void rightText(PDPageContentStream cs, PDType0Font font,
            String text, float rightX, float y, float fontSize, Color color) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(color);
        float tw = 0;
        try { tw = font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception ignored) {}
        cs.newLineAtOffset(rightX - tw, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    private static void leftText(PDPageContentStream cs, PDType0Font font,
            String text, float leftX, float y, float fontSize, Color color) throws IOException {
        String visual = shape(text);
        if (visual.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(leftX, y);
        try { cs.showText(visual); } catch (Exception e) { cs.showText("?"); }
        cs.endText();
    }

    private float measureText(PdfCtx ctx, String text, float fontSize) {
        String visual = shape(text);
        try { return ctx.font.getStringWidth(visual) / 1000 * fontSize; } catch (Exception e) { return 0; }
    }

    private PDPageContentStream cs(PdfCtx ctx) { return ctx.cs; }

    // ═══════════════════════════════════════════════════════════════
    // ARABIC TEXT PROCESSING
    // ═══════════════════════════════════════════════════════════════

    private static String shape(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            String shaped = SHAPER.shape(text);
            Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        } catch (ArabicShapingException e) { return text; }
    }

    private PDType0Font loadArabicFont(PDDocument doc) throws IOException {
        var fontStream = getClass().getResourceAsStream("/fonts/NotoSansArabic-Regular.ttf");
        if (fontStream != null) return PDType0Font.load(doc, fontStream);
        java.io.File[] candidates = {
            new java.io.File("C:/Windows/Fonts/tahoma.ttf"),
            new java.io.File("C:/Windows/Fonts/arial.ttf"),
            new java.io.File("C:/Windows/Fonts/segoeui.ttf"),
            new java.io.File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
        };
        for (java.io.File f : candidates)
            if (f.exists()) return PDType0Font.load(doc, f);
        throw new IOException("لا يوجد خط عربي متاح على النظام");
    }

    // ═══════════════════════════════════════════════════════════════
    // FORMATTING UTILITIES
    // ═══════════════════════════════════════════════════════════════

    private static String translateType(String type) {
        return switch (type) {
            case "OUT"        -> "بيع";
            case "IN"         -> "شراء";
            case "RETURN"     -> "مرتجع";
            case "DEPOSIT"    -> "إيداع";
            case "WITHDRAWAL" -> "سحب";
            default -> type;
        };
    }

    private static String unitLabel(String unit) {
        return switch (unit) {
            case "WEIGHT"  -> "وزن";
            case "PACKAGE" -> "عبوة";
            default -> "قطعة";
        };
    }

    private static String moneyClean(double x) {
        return String.format("%,.2f\u00A0ج.م", x);
    }

    private static String formatQtyWithUnit(double qty, String measurementUnit) {
        if (measurementUnit != null && !measurementUnit.isEmpty()) {
            String num = (qty == Math.rint(qty))
                ? String.format("%,d", (long) qty)
                : String.format("%,.2f", qty);
            return num + " " + measurementUnit;
        }
        // Fallback: old data without measurement_unit
        if (qty == Math.rint(qty)) return String.format("%,d", (long) qty);
        return String.format("%,.2f", qty);
    }

    private static String formatDate(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    // ═══════════════════════════════════════════════════════════════
    // PROFESSIONAL ARABIC DATE FORMATTING
    // ═══════════════════════════════════════════════════════════════

    private static final String[] DAY_NAMES_AR = {
        "الإثنين", "الثلاثاء", "الأربعاء", "الخميس",
        "الجمعة", "السبت", "الأحد"
    };

    /** Shared Arabic date parts: 'الأحد، 20 يوليو 2026م' */
    private static String formatDateArabic(LocalDate d) {
        int dayOfWeek = d.getDayOfWeek().getValue() - 1; // 0=Monday..6=Sunday
        return DAY_NAMES_AR[dayOfWeek] + "، " + d.getDayOfMonth() + " " + MONTH_NAMES_AR[d.getMonthValue() - 1] + " " + d.getYear() + "م";
    }

    /** Professional report date: 'تقرير يوم الأحد، الموافق 20 يوليو 2026م' */
    private static String formatReportDateArabic(LocalDate d) {
        return "تقرير يوم " + formatDateArabic(d);
    }

    private static final String[] MONTH_NAMES_AR = {
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    };

    /** Arabic date without day name: '20 يوليو 2026م' */
    private static String formatDateArabicDateOnly(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTH_NAMES_AR[d.getMonthValue() - 1] + " " + d.getYear() + "م";
    }

    // ═══════════════════════════════════════════════════════════════
    // SALES-ONLY PDF
    // ═══════════════════════════════════════════════════════════════
    public void exportSalesPdf(Path path) { exportSalesPdf(path, LocalDate.now()); }
    public void exportSalesPdf(Path path, LocalDate date) {
        List<TransactionRow> transactions = service.getTransactionsForDate(date);
        List<TransactionRow> sales = transactions.stream().filter(t -> "بيع".equals(t.type())).toList();
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = date; ctx.reportEndDate = date;
            ctx.reportTitle = "تقرير المبيعات"; ctx.reportType = "تقرير مبيعات";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "عدد فواتير البيع: " + sales.size());
            ctx.y -= 8;
            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "فواتير البيع", ctx.y); ctx.y -= 4;
                ctx.y = drawSalesTable(ctx, sales);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير المبيعات", e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // RETURNS-ONLY PDF
    // ═══════════════════════════════════════════════════════════════
    public void exportReturnsPdf(Path path) { exportReturnsPdf(path, LocalDate.now()); }
    public void exportReturnsPdf(Path path, LocalDate date) {
        // Matching UI returns page table: التاريخ, رقم العملية, الكود, المنتج, المباع, تم إرجاعه, السعر, العميل
        // Use the same data source as the UI (recentSales)
        java.util.List<SaleRow> sales = service.recentSales();
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = date; ctx.reportEndDate = date;
            ctx.reportTitle = "تقرير المرتجعات — المبيعات القابلة للإرجاع"; ctx.reportType = "تقرير مرتجعات";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "عدد فواتير البيع القابلة للإرجاع: " + sales.size());
            ctx.y -= 8;
            if (!sales.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "فواتير البيع القابلة للإرجاع", ctx.y); ctx.y -= 4;
                // Returns page table matching UI
                String[] headers = {"التاريخ", "رقم العملية", "الكود", "المنتج", "المباع", "تم إرجاعه", "السعر", "العميل"};
                float[] widths = {55, 50, 50, 75, 40, 45, 50, 60};
                java.util.List<String[]> data = new java.util.ArrayList<>();
                for (SaleRow sr : sales) {
                    String dt = sr.dateTime() != null && sr.dateTime().length() >= 10 ? sr.dateTime().substring(0, 10) : (sr.dateTime() != null ? sr.dateTime() : "");
                    String cn = sr.customerName() != null && !sr.customerName().isEmpty() ? sr.customerName() : "-";
                    data.add(new String[]{dt, String.valueOf(sr.transactionId()), sr.code(), sr.product(),
                        formatQtyWithUnit(sr.quantity(), ""), formatQtyWithUnit(sr.returnedQuantity(), ""),
                        moneyClean(sr.price()), cn});
                }
                ctx.y = drawTable(ctx, headers, widths, data);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير المرتجعات", e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // CASH OPERATIONS PDF
    // ═══════════════════════════════════════════════════════════════
    public void exportCashOpsPdf(Path path) { exportCashOpsPdf(path, LocalDate.now()); }
    public void exportCashOpsPdf(Path path, LocalDate date) {
        List<CashLedgerRow> cashOps = service.getAllCashLedger();
        DailyCardRow card = service.getDailyCard(date);
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = date; ctx.reportEndDate = date;
            ctx.reportTitle = "تقرير عمليات الخزينة"; ctx.reportType = "تقرير خزينة";
            newPage(ctx);

            // ── Treasury Summary Section ──
            ctx.y = drawSectionTitle(ctx, "ملخص الخزينة", ctx.y);
            ctx.y -= 4;
            float colW = CONTENT_W / 2f;
            float rowH = 20;
            double netFlow = card.sales() - card.wholesale() + card.deposit() - card.withdrawal();
            String[][] info = {
                {moneyClean(card.balance()), "الرصيد الحالي"},
                {moneyClean(card.sales()), "إجمالي المبيعات"},
                {moneyClean(card.profit()), "إجمالي الربح"},
                {moneyClean(card.wholesale()), "إجمالي المشتريات"},
                {moneyClean(card.deposit()), "إجمالي الإيرادات"},
                {moneyClean(card.withdrawal()), "إجمالي المصروفات"},
                {moneyClean(card.sales() + card.deposit()), "إجمالي الوارد"},
                {moneyClean(card.wholesale() + card.withdrawal()), "إجمالي المنصرف"},
                {moneyClean(netFlow), "صافي الحركة"}
            };
            int rows = (info.length + 1) / 2;
            ctx.y = ensureSpace(ctx, ctx.y, rows * rowH + 10);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < 2; c++) {
                    int idx = r * 2 + c;
                    if (idx >= info.length) break;
                    float x = (c == 0) ? PAGE_W - MARGIN - colW : PAGE_W - MARGIN - colW * 2;
                    cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                    drawCellLabel(ctx, info[idx][0], x + 6, ctx.y - rowH / 2 - 3, 7);
                    drawCellValue(ctx, info[idx][1], x + colW - 6, ctx.y - rowH / 2 - 3, 7.5f);
                    cs(ctx).setNonStrokingColor(C_BORDER);
                    cs(ctx).setLineWidth(0.3f);
                    cs(ctx).addRect(x, ctx.y - rowH, colW, rowH);
                    cs(ctx).stroke();
                }
                ctx.y -= rowH;
            }
            ctx.y -= 8;

            // ── Cash Operations Table ──
            ctx.y = drawSimpleInfo(ctx, "عدد العمليات: " + cashOps.size());
            ctx.y -= 8;
            if (!cashOps.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "عمليات الخزينة", ctx.y); ctx.y -= 4;
                ctx.y = drawCashTable(ctx, cashOps);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير الخزينة", e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // CUSTOMER PDF
    /** Export comprehensive customer PDF with detailed info + per-customer transactions. */
    public void exportCustomerDetailedPdf(Path path, Customer customer, List<CustomerTransaction> transactions) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير شامل للعميل: " + customer.name();
            ctx.reportType = "تقرير عميل";
            newPage(ctx);

            // ── Customer Info Grid (2 rows x 6 cols) ──
            ctx.y = drawSectionTitle(ctx, "بيانات العميل", ctx.y); ctx.y -= 4;
            float colW2 = CONTENT_W / 6f;
            float rowH2 = 18;

            String phone = customer.phone() != null && !customer.phone().isEmpty() ? customer.phone() : "—";
            String custType = customer.customerType() != null && !customer.customerType().isEmpty() ? customer.customerType() : "—";
            String addr = customer.address() != null && !customer.address().isEmpty() ? customer.address() : "—";
            String mats = customer.materials() != null && !customer.materials().isEmpty() ? customer.materials() : "—";
            String createdAt = customer.createdAt() != null ? customer.createdAt() : "—";

            int txCount = transactions.size();
            double totalSales = transactions.stream().filter(t -> "بيع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
            double totalReturns = transactions.stream().filter(t -> "مرتجع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
            double netPayments = totalSales - totalReturns; // صافي المدفوعات
            String firstDate = "—";
            String lastDate = "—";
            if (!transactions.isEmpty()) {
                // Transactions are ordered DESC (newest first)
                firstDate = transactions.get(transactions.size() - 1).dateTime().substring(0, 10);
                lastDate = transactions.get(0).dateTime().substring(0, 10);
            }

            String[][] infoGrid = {
                {customer.name(), "اسم العميل"},
                {customer.code(), "كود العميل"},
                {phone, "الهاتف"},
                {custType, "نوع العميل"},
                {addr, "العنوان"},
                {mats, "المواد"},
                {createdAt, "تاريخ الإضافة"},
                {String.valueOf(txCount), "عدد المعاملات"},
                {moneyClean(totalSales), "إجمالي المشتريات"},
                {moneyClean(totalReturns), "إجمالي المرتجعات"},
                {moneyClean(netPayments), "إجمالي المدفوعات"},
                {"0", "المديونية"},
                {firstDate, "أول معاملة"},
                {lastDate, "آخر معاملة"}
            };
            int rows = (infoGrid.length + 5) / 6; // ceil division by 6
            ctx.y = ensureSpace(ctx, ctx.y, rows * rowH2 + 10);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < 6; c++) {
                    int idx = r * 6 + c;
                    if (idx >= infoGrid.length) break;
                    float x = PAGE_W - MARGIN - (c + 1) * colW2;
                    cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                    drawCellLabel(ctx, infoGrid[idx][0], x + 4, ctx.y - rowH2 / 2 - 2, 6);
                    drawCellValue(ctx, infoGrid[idx][1], x + colW2 - 4, ctx.y - rowH2 / 2 - 2, 7);
                    cs(ctx).setNonStrokingColor(C_BORDER);
                    cs(ctx).setLineWidth(0.3f);
                    cs(ctx).addRect(x, ctx.y - rowH2, colW2, rowH2);
                    cs(ctx).stroke();
                }
                ctx.y -= rowH2;
            }
            ctx.y -= 8;

            // ── Transactions Table ──
            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 40);
                ctx.y = drawSectionTitle(ctx, "سجل المعاملات", ctx.y); ctx.y -= 4;
                String[] txHeaders = {"التاريخ", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "ملاحظات"};
                List<String[]> txData = new java.util.ArrayList<>();
                for (CustomerTransaction ct : transactions) {
                    String dt = ct.dateTime() != null && ct.dateTime().length() > 10 ? ct.dateTime().substring(0, 10) : (ct.dateTime() != null ? ct.dateTime() : "");
                    double txTotal = ct.total();
                    double qty = ct.quantity();
                    // الكمية / السعة — CustomerTransaction has no packageWeight data
                    String capDisplay = "—";
                    String countDisplay = (qty > 0 && qty == Math.rint(qty) && qty < 1e12)
                        ? String.format("%,d", (long) qty)
                        : "—";
                    txData.add(new String[]{
                        dt,
                        ct.type(),
                        ct.productName() != null ? ct.productName() : "—",
                        capDisplay,
                        countDisplay,
                        moneyClean(ct.price()),
                        moneyClean(txTotal),
                        ct.notes() != null && !ct.notes().isEmpty() ? ct.notes() : ""
                    });
                }
                // Totals row
                double totalQty = transactions.stream().mapToDouble(CustomerTransaction::quantity).sum();
                double grandTotal = transactions.stream().mapToDouble(CustomerTransaction::total).sum();
                txData.add(new String[]{
                    "الإجمالي", "", "",
                    String.format("%,.2f", totalQty),
                    String.format("%,.0f", totalQty), "", moneyClean(grandTotal), ""
                });
                drawTable(ctx, txHeaders, null, txData);
            }

            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير التقرير الشامل للعميل", e); }
    }

    // ═══════════════════════════════════════════════════════════════
    public void exportCustomerPdf(Path path, Customer customer, List<CustomerTransaction> transactions) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير العميل: " + customer.name();
            ctx.reportType = "تقرير عميل";
            newPage(ctx);
            // ── Customer Info ──
            ctx.y = drawSectionTitle(ctx, "بيانات العميل", ctx.y); ctx.y -= 4;
            float colW = CONTENT_W / 2f; float rowH = 20;
            String[][] info = {
                {customer.code(),"كود العميل"},
                {customer.name(),"اسم العميل"},
                {customer.customerType() != null && !customer.customerType().isEmpty() ? customer.customerType() : "—","نوع العميل"},
                {customer.phone(),"رقم الهاتف"},
                {customer.address() != null && !customer.address().isEmpty() ? customer.address() : "—","العنوان"},
                {customer.materials(),"المواد"},
                {customer.createdAt() != null ? customer.createdAt() : "—","تاريخ الإضافة"},
                {String.valueOf(transactions.size()),"عدد المعاملات"}
            };
            double totalSales = transactions.stream().filter(t -> "بيع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
            double totalReturns = transactions.stream().filter(t -> "مرتجع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
            String[][] extended = new String[info.length + 2][2];
            System.arraycopy(info, 0, extended, 0, info.length);
            extended[info.length] = new String[]{"إجمالي المشتريات", moneyClean(totalSales)};
            extended[info.length + 1] = new String[]{"إجمالي المرتجعات", moneyClean(totalReturns)};
            info = extended;
            int rows = (info.length + 1) / 2;
            ctx.y = ensureSpace(ctx, ctx.y, rows * rowH + 10);
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < 2; col++) {
                    int idx = row * 2 + col;
                    if (idx >= info.length) break;
                    float x = (col == 0) ? PAGE_W - MARGIN - colW : PAGE_W - MARGIN - colW * 2;
                    cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                    drawCellLabel(ctx, info[idx][0], x + 6, ctx.y - rowH / 2 - 3, 7);
                    drawCellValue(ctx, info[idx][1], x + colW - 6, ctx.y - rowH / 2 - 3, 7.5f);
                    cs(ctx).setNonStrokingColor(C_BORDER); cs(ctx).setLineWidth(0.3f);
                    cs(ctx).addRect(x, ctx.y - rowH, colW, rowH); cs(ctx).stroke();
                }
                ctx.y -= rowH;
            }
            ctx.y -= 10;
            // ── Transactions Table ──
            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "سجل المعاملات", ctx.y); ctx.y -= 4;
                String[] headers = {"التاريخ", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "ملاحظات"};
                float[] widths = {70, 42, 110, 48, 35, 55, 60, 60};
                List<String[]> data = new ArrayList<>();
                for (CustomerTransaction ct : transactions) {
                    String dt = ct.dateTime() != null && ct.dateTime().length() > 10 ? ct.dateTime().substring(0, 10) : (ct.dateTime() != null ? ct.dateTime() : "");
                    // الكمية / السعة — CustomerTransaction has no packageWeight data
                    String capDisplay = "—";
                    double qty = ct.quantity();
                    String countDisplay = (qty > 0 && qty == Math.rint(qty) && qty < 1e12)
                        ? String.format("%,d", (long) qty)
                        : "—";
                    data.add(new String[]{dt, ct.type(), ct.productName(),
                        capDisplay, countDisplay, moneyClean(ct.price()),
                        moneyClean(ct.total()), ct.notes() != null ? ct.notes() : ""});
                }
                drawTable(ctx, headers, widths, data);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير العميل", e); }
    }

    public void exportSupplierPdf(Path path, InventoryService.Supplier supplier, List<InventoryService.SupplierTransaction> transactions) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير المورد: " + supplier.name();
            ctx.reportType = "تقرير مورد";
            newPage(ctx);
            // ── Supplier Info ──
            ctx.y = drawSectionTitle(ctx, "بيانات المورد", ctx.y); ctx.y -= 4;
            float colW = CONTENT_W / 2f; float rowH = 20;
            String[][] info = {
                {supplier.code(),"كود المورد"},
                {supplier.name(),"اسم المورد"},
                {supplier.phone(),"رقم الهاتف"},
                {supplier.specialty() != null && !supplier.specialty().isEmpty() ? supplier.specialty() : "—","التخصص"},
                {supplier.address() != null && !supplier.address().isEmpty() ? supplier.address() : "—","العنوان"},
                {supplier.createdAt() != null ? supplier.createdAt() : "—","تاريخ الإضافة"},
                {String.valueOf(transactions.size()),"عدد المعاملات"}
            };
            double totalPurchases = transactions.stream().mapToDouble(InventoryService.SupplierTransaction::total).sum();
            String[][] extended = new String[info.length + 1][2];
            System.arraycopy(info, 0, extended, 0, info.length);
            extended[info.length] = new String[]{"إجمالي المشتريات", moneyClean(totalPurchases)};
            info = extended;
            int rows = (info.length + 1) / 2;
            ctx.y = ensureSpace(ctx, ctx.y, rows * rowH + 10);
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < 2; col++) {
                    int idx = row * 2 + col;
                    if (idx >= info.length) break;
                    float x = (col == 0) ? PAGE_W - MARGIN - colW : PAGE_W - MARGIN - colW * 2;
                    cs(ctx).setNonStrokingColor(C_TEXT_SEC);
                    drawCellLabel(ctx, info[idx][0], x + 6, ctx.y - rowH / 2 - 3, 7);
                    drawCellValue(ctx, info[idx][1], x + colW - 6, ctx.y - rowH / 2 - 3, 7.5f);
                    cs(ctx).setNonStrokingColor(C_BORDER); cs(ctx).setLineWidth(0.3f);
                    cs(ctx).addRect(x, ctx.y - rowH, colW, rowH); cs(ctx).stroke();
                }
                ctx.y -= rowH;
            }
            ctx.y -= 10;
            // ── Transactions Table ──
            if (!transactions.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "سجل المشتريات", ctx.y); ctx.y -= 4;
                String[] headers = {"التاريخ", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "ملاحظات"};
                float[] widths = {70, 42, 110, 55, 35, 55, 60, 60};
                List<String[]> data = new ArrayList<>();
                for (InventoryService.SupplierTransaction st : transactions) {
                    String dt = st.dateTime() != null && st.dateTime().length() > 10 ? st.dateTime().substring(0, 10) : (st.dateTime() != null ? st.dateTime() : "");
                    double qty = st.quantity();
                    String ut = st.unitType() != null ? st.unitType() : "COUNT";
                    double pw = st.packageWeight();
                    // الكمية / السعة = سعة الوحدة (packageWeight) المسجلة عند إضافة الوارد
                    String capDisplay = "—";
                    if (pw > 0) {
                        capDisplay = String.format("%,.2f", pw);
                    }
                    String countDisplay = (("COUNT".equals(ut) || "PACKAGE".equals(ut)) && qty > 0)
                        ? String.format("%,.0f", qty)
                        : "—";
                    data.add(new String[]{dt, st.type(), st.productName(),
                        capDisplay, countDisplay, moneyClean(st.price()),
                        moneyClean(st.total()), st.notes() != null ? st.notes() : ""});
                }
                drawTable(ctx, headers, widths, data);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير المورد", e); }
    }

    // ═══════════════════════════════════════════════════════════════
    // ALL CUSTOMERS LIST PDF
    // ═══════════════════════════════════════════════════════════════
    public void exportAllCustomersPdf(Path path, List<Customer> customers) {
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "قائمةالعملاء"; ctx.reportType = "تقرير عملاء";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "عدد العملاء: " + customers.size());
            ctx.y -= 8;
            if (!customers.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع العملاء", ctx.y); ctx.y -= 4;
                String[] headers = {"تاريخ الإضافة", "الكود", "الاسم", "النوع", "الهاتف", "العنوان", "المواد"};
                float[] widths = {55, 45, 65, 45, 50, 55, 70};
                List<String[]> data = new ArrayList<>();
                for (Customer c : customers) {
                    String dt = c.createdAt() != null ? c.createdAt() : "";
                    if (dt.length() >= 10) dt = dt.substring(0, 10);
                    String addr = c.address() != null && !c.address().isEmpty() ? c.address() : "—";
                    data.add(new String[]{dt, c.code(), c.name(),
                        c.customerType() != null && !c.customerType().isEmpty() ? c.customerType() : "—",
                        c.phone(), addr, c.materials()});
                }
                drawTable(ctx, headers, widths, data);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير قائمة العملاء", e); }
    }

    public void exportSuppliersPdf(Path path) {
        java.util.List<InventoryService.Supplier> suppliers = service.allSuppliers();
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير الموردين"; ctx.reportType = "تقرير موردين";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "عدد الموردين: " + suppliers.size());
            ctx.y -= 8;
            if (!suppliers.isEmpty()) {
                ctx.y = ensureSpace(ctx, ctx.y, 60);
                ctx.y = drawSectionTitle(ctx, "جميع الموردين", ctx.y); ctx.y -= 4;
                // Matching UI: تاريخ الإضافة, الكود, اسم المورد, رقم الهاتف, التخصص, العنوان
                String[] headers = {"تاريخ الإضافة", "الكود", "اسم المورد", "رقم الهاتف", "التخصص", "العنوان"};
                float[] widths = {58, 45, 80, 60, 60, 60};
                List<String[]> data = new ArrayList<>();
                for (InventoryService.Supplier s : suppliers) {
                    String dt = s.createdAt() != null ? s.createdAt() : "";
                    if (dt.length() >= 10) dt = dt.substring(0, 10);
                    data.add(new String[]{dt, s.code(), s.name(), s.phone(),
                        s.specialty() != null && !s.specialty().isEmpty() ? s.specialty() : "—",
                        s.address() != null && !s.address().isEmpty() ? s.address() : "—"});
                }
                drawTable(ctx, headers, widths, data);
            }
            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير الموردين", e); }
    }

    /** Export comprehensive PDF with detailed per-supplier info + transactions table. */
    public void exportSuppliersDetailedPdf(java.nio.file.Path path) {
        java.util.List<InventoryService.Supplier> suppliers = service.allSuppliers();
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقريرالموردين"; ctx.reportType = "تقرير موردين";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "عدد الموردين: " + suppliers.size());
            ctx.y -= 10;

            for (InventoryService.Supplier s : suppliers) {
                java.util.List<InventoryService.SupplierTransaction> txs = service.supplierTransactions(s.id());

                // Check if we need a new page (need at least ~150pt for supplier header + info grid)
                ctx.y = ensureSpace(ctx, ctx.y, 150);

                // ── Supplier Header ──
                ctx.y = drawSectionTitle(ctx, "المورد: " + s.name(), ctx.y);
                ctx.y -= 6;

                // ── Supplier Info Grid — 6 صفوف، كل صف مستطيلين (label + value)، حدود واضحة ──
                String phone = s.phone() != null && !s.phone().isEmpty() ? s.phone() : "—";
                String specialty = s.specialty() != null && !s.specialty().isEmpty() ? s.specialty() : "—";
                String addr = s.address() != null && !s.address().isEmpty() ? s.address() : "—";
                String createdAt = s.createdAt() != null ? s.createdAt() : "—";

                // Calculate stats
                int txCount = txs.size();
                double totalPurchases = txs.stream().mapToDouble(InventoryService.SupplierTransaction::total).sum();
                String firstDate = txs.isEmpty() ? "—" : txs.get(txs.size() - 1).dateTime().substring(0, 10);
                String lastDate = txs.isEmpty() ? "—" : txs.get(0).dateTime().substring(0, 10);

                // ── Supplier Info Grid — 6 صفوف، كل صف مستطيلين (label + value)، حدود واضحة ──
                float cellW = CONTENT_W / 2f;
                float infoRowH = 22;
                String createdAtDisp = createdAt != null && !createdAt.isEmpty() ? createdAt : "—";
                String paymentStatus = "مدفوع";
                String remainingAmount = "0";
                String txCountStr = String.valueOf(txCount);

                // Row 1: [الهاتف] | [التخصص]
                // Row 2: [العنوان] | [تاريخ ووقت العملية]
                // Row 3: [إجمالي المشتريات] | [إجمالي المدفوعات]
                // Row 4: [المبالغ المتبقية] | [عدد المعاملات]
                // Row 5: [أول معاملة] | [آخر معاملة]
                // Row 6: [حالة الدفع] (full width)
                String[][] infoRows = {
                    
    {phone, "الهاتف", specialty, "التخصص"},
    {createdAtDisp, "تاريخ ووقت العملية", addr, "العنوان"},
    {moneyClean(totalPurchases), "إجمالي المشتريات", moneyClean(totalPurchases), "إجمالي المدفوعات"},
    {txCountStr, "عدد المعاملات", remainingAmount, "المبالغ المتبقية"},
    {lastDate, "آخر معاملة", firstDate, "أول معاملة"},
    {"", "", paymentStatus, "حالة الدفع"}
 // Row 6 fills full width
                };

                ctx.y = ensureSpace(ctx, ctx.y, infoRows.length * infoRowH + 10);

                for (int r = 0; r < infoRows.length; r++) {
                    // Cell 1 (right): label at right + value
                    float x1 = PAGE_W - MARGIN - cellW;
                    String label1 = infoRows[r][0];
                    String val1 = infoRows[r][1];
                    // Label cell 1
                    cs(ctx).setNonStrokingColor(C_PRIMARY);
                    drawCellLabel(ctx, label1, x1 + 6, ctx.y - infoRowH / 2 - 3, 7.5f);
                    // Value cell 1
                    cs(ctx).setNonStrokingColor(C_TEXT);
                    drawCellValue(ctx, val1, x1 + cellW - 6, ctx.y - infoRowH / 2 - 3, 7.5f);
                    // Border cell 1
                    cs(ctx).setNonStrokingColor(C_BORDER);
                    cs(ctx).setLineWidth(0.4f);
                    cs(ctx).addRect(x1, ctx.y - infoRowH, cellW, infoRowH);
                    cs(ctx).stroke();

                    // Cell 2 (left): label at right + value (only if has label)
                    if (r < infoRows.length - 1 || !infoRows[r][2].isEmpty()) {
                        float x2 = PAGE_W - MARGIN - cellW * 2;
                        String label2 = infoRows[r][2];
                        String val2 = infoRows[r][3];
                        cs(ctx).setNonStrokingColor(C_PRIMARY);
                        drawCellLabel(ctx, label2, x2 + 6, ctx.y - infoRowH / 2 - 3, 7.5f);
                        cs(ctx).setNonStrokingColor(C_TEXT);
                        drawCellValue(ctx, val2, x2 + cellW - 6, ctx.y - infoRowH / 2 - 3, 7.5f);
                        cs(ctx).setNonStrokingColor(C_BORDER);
                        cs(ctx).setLineWidth(0.4f);
                        cs(ctx).addRect(x2, ctx.y - infoRowH, cellW, infoRowH);
                        cs(ctx).stroke();
                        // Separator between the two cells
                        cs(ctx).setLineWidth(0.3f);
                        cs(ctx).addRect(x2 + cellW, ctx.y - infoRowH + 2, 0, infoRowH - 4);
                        cs(ctx).stroke();
                    } else {
                        // Row 6: single cell spanning full width
                        float x2 = PAGE_W - MARGIN - cellW * 2;
                        cs(ctx).setNonStrokingColor(C_BORDER);
                        cs(ctx).setLineWidth(0.4f);
                        cs(ctx).addRect(x2, ctx.y - infoRowH, cellW * 2, infoRowH);
                        cs(ctx).stroke();
                    }

                    ctx.y -= infoRowH;
                }
                ctx.y -= 8;

                // ── Transactions Table ──
                if (!txs.isEmpty()) {
                    ctx.y = ensureSpace(ctx, ctx.y, 40);
                    String[] txHeaders = {"التاريخ", "رقم العملية", "النوع", "المنتج", "الكمية / السعة", "العدد", "السعر", "الإجمالي", "حالة الدفع", "ملاحظات"};
                    List<String[]> txData = new java.util.ArrayList<>();
                    for (InventoryService.SupplierTransaction st : txs) {
                        String dtRaw = st.dateTime() != null && st.dateTime().length() > 10 ? st.dateTime().substring(0, 16) : (st.dateTime() != null ? st.dateTime() : "");
                        // تنسيق التاريخ والوقت في سطرين منفصلين
                        String dt = "";
                        if (dtRaw.contains(" ")) {
                            String[] parts = dtRaw.split(" ", 2);
                            dt = parts[0] + "\n" + parts[1];
                        } else {
                            dt = dtRaw;
                        }
                        double qty = st.quantity();
                        // الكمية / السعة = سعة الوحدة (packageWeight) من شاشة إضافة الوارد
                        double pw = st.packageWeight();
                        String ut = st.unitType() != null ? st.unitType() : "COUNT";
                        String capDisplay = "—";
                        if ("PACKAGE".equals(ut) && pw > 0) {
                            capDisplay = String.format("%,.2f", pw);
                        }
                        // العدد — for COUNT/PACKAGE products, show quantity; otherwise "—"
                        String countDisplay = (("COUNT".equals(ut) || "PACKAGE".equals(ut)) && qty > 0)
                            ? String.format("%,.0f", qty)
                            : "—";
                        txData.add(new String[]{
                            dt,
                            String.valueOf(st.id()),
                            "IN".equals(st.type()) ? "شراء" : st.type(),
                            st.productName() != null ? st.productName() : "—",
                            capDisplay,
                            countDisplay,
                            moneyClean(st.price()),
                            moneyClean(st.total()),
                            "مدفوع",
                            st.notes() != null && !st.notes().isEmpty() ? st.notes() : ""
                        });
                    }
                    // Add totals row
                    double totalQty = txs.stream().mapToDouble(InventoryService.SupplierTransaction::quantity).sum();
                    txData.add(new String[]{
                        "الإجمالي", "", "", "",
                        "",  // الكمية/السعة — cannot sum different unit capacities
                        String.format("%,.0f", totalQty),  // العدد — total count
                        "", moneyClean(totalPurchases), "", ""
                    });
                    drawTable(ctx, txHeaders, null, txData);
                    ctx.y -= 10;
                }
            }

            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير التقرير الشامل للموردين", e); }
    }

    /** Export professional PDF report with all tool rentals grouped by day. */
    public void exportToolsPdf(java.nio.file.Path path) {
        java.util.List<InventoryService.RentalRecord> rentals = service.getAllRentals();
        try (PDDocument doc = new PDDocument()) {
            PdfCtx ctx = new PdfCtx(); ctx.doc = doc; ctx.font = loadArabicFont(doc);
            ctx.reportDate = LocalDate.now(); ctx.reportEndDate = LocalDate.now();
            ctx.reportTitle = "تقرير العدد والتأجير"; ctx.reportType = "تقرير تأجير";
            newPage(ctx);
            ctx.y = drawSimpleInfo(ctx, "إجمالي عمليات التأجير: " + rentals.size());
            ctx.y -= 10;

            // ── Group rentals by date ──
            java.util.Map<String, java.util.List<InventoryService.RentalRecord>> byDate = new java.util.LinkedHashMap<>();
            for (InventoryService.RentalRecord r : rentals) {
                byDate.computeIfAbsent(r.date(), k -> new java.util.ArrayList<>()).add(r);
            }

            for (java.util.Map.Entry<String, java.util.List<InventoryService.RentalRecord>> entry : byDate.entrySet()) {
                String date = entry.getKey();
                java.util.List<InventoryService.RentalRecord> dayRentals = entry.getValue();

                // ── Section per day ──
                ctx.y = ensureSpace(ctx, ctx.y, 50);
                String dayLabel = date;
                try {
                    LocalDate ld = LocalDate.parse(date);
                    dayLabel = formatDateArabicDateOnly(ld);
                } catch (Exception ignored) {}
                double dayTotal = dayRentals.stream().mapToDouble(InventoryService.RentalRecord::price).sum();
                ctx.y = drawSectionTitle(ctx, dayLabel + "  —  " + dayRentals.size() + " تأجيرات  —  إجمالي: " + moneyClean(dayTotal), ctx.y);
                ctx.y -= 4;

                // ── Day rentals table ──
                String[] headers = {"التاريخ", "الوقت", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الحالة"};
                String[][] data = new String[dayRentals.size()][];
                for (int i = 0; i < dayRentals.size(); i++) {
                    InventoryService.RentalRecord r = dayRentals.get(i);
                    String sizeVal = r.size() != null && !r.size().isEmpty() ? r.size() : "—";
                    String statusVal = "تم تأجيرها".equals(r.status()) ? "تم تأجيرها" : (r.status() != null && !r.status().isEmpty() ? r.status() : "—");
                    data[i] = new String[]{
                        r.date(),
                        r.time(),
                        r.toolCode() != null && !r.toolCode().isEmpty() ? r.toolCode() : "—",
                        r.toolName(),
                        sizeVal,
                        r.renterName() != null && !r.renterName().isEmpty() ? r.renterName() : "—",
                        moneyClean(r.price()),
                        statusVal
                    };
                }
                ctx.y = drawTable(ctx, headers, null, java.util.Arrays.asList(data));
                ctx.y -= 10;
            }

            closeAndAddFooters(ctx); doc.save(path.toFile());
        } catch (IOException e) { throw new RuntimeException("فشل تصدير تقرير العدد والتأجير", e); }
    }

}
