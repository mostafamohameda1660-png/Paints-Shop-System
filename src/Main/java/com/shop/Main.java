package com.shop;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import com.shop.database.DatabaseInitializer;
import com.shop.model.Category;
import com.shop.service.CategoryService;
import com.shop.service.InventoryService;
import com.shop.service.InventoryService.Customer;
import com.shop.service.InventoryService.CustomerTransaction;
import com.shop.service.InventoryService.DailyCard;
import com.shop.service.InventoryService.DailyReportArchive;
import com.shop.service.InventoryService.DailyTransaction;
import com.shop.service.InventoryService.IncomingItem;
import com.shop.service.InventoryService.MonthlyReport;
import com.shop.service.InventoryService.ProductRow;
import com.shop.service.InventoryService.SaleItem;
import com.shop.service.InventoryService.SaleRow;
import com.shop.service.InventoryService.ToolRow;
import com.shop.service.InventoryService.TreasurySummary;
import com.shop.service.InventoryService.YearlyReport;
import com.shop.service.ReportService;
import com.shop.utils.NumberUtil;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;

public final class Main extends Application {
    private static final double TAX_RATE = 1.14;

    private final InventoryService service = new InventoryService();
    private final ReportService reports = new ReportService(service);
    private final CategoryService categoryService = new CategoryService();
    private final TableView<ProductRow> stockTable = new TableView<>();
    private final TableView<SaleRow> salesTable = new TableView<>();
    private final TableView<DailyTransaction> txTable = new TableView<>();
    private final TableView<Customer> customerTable = new TableView<>();
    private final Label homeDate = new Label();
    private final Label treasuryToday = new Label();
    private final Label totalProfitLabel = new Label();
    private TabPane tabPane;
    private Stage stage;
    // Timer removed — report generation handled by checkAndGenerateReports() on startup
    private final List<Runnable> refreshCallbacks = new ArrayList<>();
    private VBox customerReportsBox;
    private VBox supplierReportsBox;
    private Runnable refreshToolsCards = () -> {};
    private Runnable refreshDailyArchives = () -> {};
    private Runnable refreshMonthlyArchives = () -> {};
    private final TableView<InventoryService.CashLedgerRow> cashLedgerTable = new TableView<>();

    // Track tab indices
    private static final int TAB_HOME = 0;
    private static final int TAB_STOCK = 1;
    private static final int TAB_TOOLS = 7;
    private static final int TAB_SUPPLIERS = 8;
    private static final int TAB_CUSTOMERS = 9;
    private static final int TAB_REPORTS = 10;
    private static final int TAB_SETTINGS = 11;

    @Override
    public void start(Stage window) {
        stage = window;
        DatabaseInitializer.initialize();

        service.checkAndGenerateReports();

        BorderPane root = new BorderPane();
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        root.setTop(header());

        tabPane = new TabPane(
            homePage(), stockPage(), incomingPage(), salesPage(),
            returnsPage(), cashPage(), transactionsPage(),
            toolsPage(), suppliersPage(), customersPage(), reportsPage(), settingsPage()
        );
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        root.setCenter(tabPane);

        // Auto-refresh tabs when switching to them (propagate changes without restart)
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;
            Platform.runLater(() -> reload());
        });

        Scene scene = new Scene(root, 1280, 800);
        scene.getStylesheets().add("data:text/css," + css());
        window.setTitle("مؤسسة الحمد للمقاولات والتوريدات");
        window.setScene(scene);
        window.show();
        reload();
    }

    @Override
    public void stop() {
    }

    private HBox header() {
        Label title = new Label("مؤسسة الحمد للمقاولات والتوريدات");
        title.getStyleClass().add("title");

        TextField search = new TextField();
        search.setPromptText("البحث بالكود");
        search.setPrefWidth(350);

        // Use a generic ListView<String> with a custom cell to show type + details
        ListView<String> suggestions = new ListView<>();
        suggestions.setPrefHeight(200);
        suggestions.setPrefWidth(450);
        Popup popup = new Popup();
        popup.getContent().add(suggestions);
        popup.setAutoHide(true);

        // Tri-state holder: "product" | "customer" | "supplier"
        final String[] foundType = {""};
        final int[] foundId = {-1};

        search.textProperty().addListener((obs, o, n) -> {
            if (n == null || n.isBlank()) { popup.hide(); return; }
            String txt = n.trim();

            // ── Try each lookup (most specific first) ──
            // If code starts with SP → supplier
            // If code starts with CL → customer
            // Otherwise → product
            String upper = txt.toUpperCase();

            if (upper.startsWith("SP")) {
                // Try supplier first
                InventoryService.Supplier sup = service.supplierByCode(txt);
                if (sup != null) {
                    foundType[0] = "supplier";
                    foundId[0] = sup.id();
                    suggestions.setItems(FXCollections.observableArrayList(
                        "📍 مورد | " + sup.code() + " | " + sup.name() + " | " + sup.phone()
                    ));
                    suggestions.setCellFactory(lv -> new ListCell<>() {
                        @Override protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item);
                        }
                    });
                    if (!popup.isShowing()) {
                        var bounds = search.localToScreen(search.getBoundsInLocal());
                        if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                    }
                    return;
                }
            } else if (upper.startsWith("CL")) {
                // Try customer
                Customer cust = service.customerByCode(txt);
                if (cust != null) {
                    foundType[0] = "customer";
                    foundId[0] = cust.id();
                    suggestions.setItems(FXCollections.observableArrayList(
                        "👤 عميل | " + cust.code() + " | " + cust.name() + " | " + cust.phone()
                    ));
                    suggestions.setCellFactory(lv -> new ListCell<>() {
                        @Override protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item);
                        }
                    });
                    if (!popup.isShowing()) {
                        var bounds = search.localToScreen(search.getBoundsInLocal());
                        if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                    }
                    return;
                }
            }

            // Default: try product (also catches any code format)
            List<ProductRow> productResults = service.products(txt);
            if (!productResults.isEmpty()) {
                foundType[0] = "product";
                foundId[0] = productResults.get(0).id();
                List<String> items = new ArrayList<>();
                for (ProductRow pr : productResults) {
                    items.add("📦 منتج | " + pr.code() + " | " + pr.name());
                }
                suggestions.setItems(FXCollections.observableArrayList(items));
                suggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item);
                    }
                });
                if (!popup.isShowing()) {
                    var bounds = search.localToScreen(search.getBoundsInLocal());
                    if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                }
                return;
            }

            // Fallback: try customer/supplier by partial match
            if (!upper.startsWith("SP") && !upper.startsWith("CL")) {
                Customer cust = service.customerByCode(txt);
                if (cust != null) {
                    foundType[0] = "customer";
                    foundId[0] = cust.id();
                    suggestions.setItems(FXCollections.observableArrayList(
                        "👤 عميل | " + cust.code() + " | " + cust.name()
                    ));
                    suggestions.setCellFactory(lv -> new ListCell<>() {
                        @Override protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item);
                        }
                    });
                    if (!popup.isShowing()) {
                        var bounds = search.localToScreen(search.getBoundsInLocal());
                        if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                    }
                    return;
                }
                InventoryService.Supplier sup = service.supplierByCode(txt);
                if (sup != null) {
                    foundType[0] = "supplier";
                    foundId[0] = sup.id();
                    suggestions.setItems(FXCollections.observableArrayList(
                        "📍 مورد | " + sup.code() + " | " + sup.name()
                    ));
                    suggestions.setCellFactory(lv -> new ListCell<>() {
                        @Override protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item);
                        }
                    });
                    if (!popup.isShowing()) {
                        var bounds = search.localToScreen(search.getBoundsInLocal());
                        if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                    }
                    return;
                }
            }

            // ── Try partial matching before giving up ──
            // Search products, customers, and suppliers by partial match
            List<ProductRow> partialProducts = service.products(txt);
            List<Customer> partialCustomers = service.customersByCodeLike(txt);
            List<InventoryService.Supplier> partialSuppliers = service.suppliersByCodeLike(txt);

            boolean hasPartial = !partialProducts.isEmpty() || !partialCustomers.isEmpty() || !partialSuppliers.isEmpty();

            if (hasPartial) {
                List<String> combinedItems = new ArrayList<>();
                // Track the first match for direct navigation
                if (!partialProducts.isEmpty()) {
                    foundType[0] = "product";
                    foundId[0] = partialProducts.get(0).id();
                    for (ProductRow pr : partialProducts) {
                        combinedItems.add("📦 منتج | " + pr.code() + " | " + pr.name());
                    }
                }
                if (!partialCustomers.isEmpty()) {
                    if (foundType[0].isEmpty()) {
                        foundType[0] = "customer";
                        foundId[0] = partialCustomers.get(0).id();
                    }
                    for (Customer c : partialCustomers) {
                        combinedItems.add("👤 عميل | " + c.code() + " | " + c.name() + " | " + c.phone());
                    }
                }
                if (!partialSuppliers.isEmpty()) {
                    if (foundType[0].isEmpty()) {
                        foundType[0] = "supplier";
                        foundId[0] = partialSuppliers.get(0).id();
                    }
                    for (InventoryService.Supplier s : partialSuppliers) {
                        combinedItems.add("📍 مورد | " + s.code() + " | " + s.name() + " | " + s.phone());
                    }
                }
                suggestions.setItems(FXCollections.observableArrayList(combinedItems));
                suggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item);
                    }
                });
                if (!popup.isShowing()) {
                    var bounds = search.localToScreen(search.getBoundsInLocal());
                    if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
                }
                return;
            }

            // Nothing found — show message in popup
            foundType[0] = "";
            foundId[0] = -1;
            suggestions.setItems(FXCollections.observableArrayList("لا توجد نتائج مطابقة."));
            suggestions.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item);
                        setStyle("-fx-text-fill:#999;-fx-font-style:italic;");
                    }
                }
            });
            if (!popup.isShowing()) {
                var bounds = search.localToScreen(search.getBoundsInLocal());
                if (bounds != null) popup.show(search, bounds.getMinX(), bounds.getMaxY());
            }
        });

        suggestions.setOnMouseClicked(e -> {
            String sel = suggestions.getSelectionModel().getSelectedItem();
            if (sel == null || foundType[0].isEmpty()) return;
            popup.hide();
            search.clear();

            switch (foundType[0]) {
                case "product" -> {
                    int pid = foundId[0];
                    tabPane.getSelectionModel().select(TAB_STOCK);
                    reloadStock("");
                    Platform.runLater(() -> {
                        for (int i = 0; i < stockTable.getItems().size(); i++) {
                            if (stockTable.getItems().get(i).id() == pid) {
                                stockTable.getSelectionModel().select(i);
                                stockTable.scrollTo(i);
                                break;
                            }
                        }
                    });
                }
                case "customer" -> {
                    tabPane.getSelectionModel().select(TAB_CUSTOMERS);
                    reload();
                }
                case "supplier" -> {
                    tabPane.getSelectionModel().select(TAB_SUPPLIERS);
                    reload();
                }
            }
        });

        HBox h = new HBox(12, title, search);
        h.getStyleClass().add("top");
        HBox.setHgrow(search, Priority.ALWAYS);
        h.setAlignment(Pos.CENTER_RIGHT);
        return h;
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. HOMEPAGE — Professional dashboard with stat cards
    // ═══════════════════════════════════════════════════════════════
    private final Label statProducts = new Label();
    private final Label statSales = new Label();
    private final Label statProfit = new Label();
    private final Label statWholesale = new Label();
    private final Label statBalance = new Label();
    private final Label statTransactions = new Label();
    private final Label statDeposit = new Label();
    private final Label statWithdrawal = new Label();
    private final VBox newCustomersBox = new VBox(6);
    private final VBox todayRentalsBox = new VBox(6);
    // Customer form materials checkboxes (refreshable when categories change)
    private VBox customerMaterialsBox;
    private List<CheckBox> customerMaterialCheckBoxes = new ArrayList<>();

    private void refreshCustomerMaterials() {
        if (customerMaterialsBox == null) return;
        customerMaterialsBox.getChildren().clear();
        Label matTitle = new Label("المواد (اختر من الأصناف المسجلة):*");
        matTitle.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:#a73c3c;");
        customerMaterialsBox.getChildren().add(matTitle);
        customerMaterialCheckBoxes.clear();
        String cbStyle = "-fx-font-size:12px;-fx-text-fill:#333;-fx-padding:2 0;";
        boolean hasAny = false;
        for (Category cat : categoryService.getAllCategories()) {
            String catName = cat.getName();
            if (catName == null || catName.trim().isEmpty()) continue;
            hasAny = true;
            CheckBox cb = new CheckBox(catName);
            cb.setStyle(cbStyle);
            customerMaterialCheckBoxes.add(cb);
            customerMaterialsBox.getChildren().add(cb);
        }
        if (!hasAny) {
            Label emptyLbl = new Label("لا توجد تخصصات مسجلة.");
            emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:12px;");
            customerMaterialsBox.getChildren().add(emptyLbl);
        }
    }

    private Tab homePage() {
        homeDate.setStyle("-fx-font-size:22px;-fx-font-weight:bold;-fx-text-fill:white;");

        // ── Stat cards grid ──
        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:16 20;" +
            "-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);";
        String valStyle = "-fx-font-size:20px;-fx-font-weight:bold;";
        String lblStyle = "-fx-font-size:11px;-fx-text-fill:#888;";

        VBox card1 = createDashboardCard("المنتجات", statProducts, lblStyle, valStyle, cardStyle, "#1b7895");
        VBox card2 = createDashboardCard("المبيعات", statSales, lblStyle, valStyle, cardStyle, "#27a068");
        VBox card3 = createDashboardCard("الربح", statProfit, lblStyle, valStyle, cardStyle, "#c07030");
        VBox card4 = createDashboardCard("الجملة", statWholesale, lblStyle, valStyle, cardStyle, "#7050a0");
        VBox card5 = createDashboardCard("الخزنة", statBalance, lblStyle, valStyle, cardStyle, "#1b7895");
        VBox card6 = createDashboardCard("المعاملات", statTransactions, lblStyle, valStyle, cardStyle, "#183b57");
        VBox card7 = createDashboardCard("المبلغ المودع", statDeposit, lblStyle, valStyle, cardStyle, "#27a068");
        VBox card8 = createDashboardCard("المبلغ المسحوب", statWithdrawal, lblStyle, valStyle, cardStyle, "#a73c3c");

        HBox row1 = new HBox(12, card1, card2, card3, card4);
        HBox row2 = new HBox(12, card5, card6, card7, card8);
        row1.setAlignment(Pos.CENTER); row2.setAlignment(Pos.CENTER);
        HBox.setHgrow(card1, Priority.ALWAYS); HBox.setHgrow(card2, Priority.ALWAYS);
        HBox.setHgrow(card3, Priority.ALWAYS); HBox.setHgrow(card4, Priority.ALWAYS);
        HBox.setHgrow(card5, Priority.ALWAYS); HBox.setHgrow(card6, Priority.ALWAYS);
        HBox.setHgrow(card7, Priority.ALWAYS);
        HBox.setHgrow(card8, Priority.ALWAYS);

        // ── New Customers Card (improved design) ──
        Label newCustTitle = new Label("العملاء");
        newCustTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        newCustomersBox.setPadding(new Insets(10, 5, 5, 5));
        newCustomersBox.setMinHeight(120);
        VBox newCustCard = new VBox(10, newCustTitle, newCustomersBox);
        newCustCard.setStyle(cardStyle);
        newCustCard.setPadding(new Insets(15));
        newCustCard.setMinHeight(160);
        VBox.setVgrow(newCustomersBox, Priority.ALWAYS);

        // ── Decorative title frame ──
        HBox titleFrame = new HBox(homeDate);
        titleFrame.setAlignment(Pos.CENTER);
        titleFrame.setStyle("-fx-background-color:linear-gradient(to left,#183b57,#1a5276,#276c87);" +
            "-fx-background-radius:10;-fx-padding:14 40;" +
            "-fx-effect:dropshadow(gaussian,#00000025,10,0,0,2);" +
            "-fx-border-color:linear-gradient(to left,#1b7895,#183b57);" +
            "-fx-border-radius:10;-fx-border-width:2;");

        // ── Cash operation form (type selection + amount + execute) ──
        Label cashFormTitle = new Label("إجراء عملية خزينة");
        cashFormTitle.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        ComboBox<String> cashTypeCombo = new ComboBox<>(FXCollections.observableArrayList("إيداع", "سحب"));
        cashTypeCombo.setPromptText("نوع العملية");
        cashTypeCombo.setPrefWidth(150);
        cashTypeCombo.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        TextField cashPersonName = input("المودع / الساحب *");
        cashPersonName.setPrefWidth(180);
        TextField cashAmount = numericInput("المبلغ");
        cashAmount.setPrefWidth(180);
        TextField cashNote = input("ملاحظات");
        cashNote.setPrefWidth(200);
        Button executeCashBtn = new Button("تنفيذ");
        executeCashBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        executeCashBtn.setOnAction(e -> {
            String type = cashTypeCombo.getValue();
            if (type == null) { warn("اختر نوع العملية"); return; }
            String cashType = "إيداع".equals(type) ? "DEPOSIT" : "WITHDRAWAL";
            doCash(cashType, cashPersonName, cashAmount, cashNote);
        });
        HBox cashFormRow = new HBox(8, cashTypeCombo, cashPersonName, cashAmount, cashNote, new Region(), executeCashBtn);
        cashFormRow.setAlignment(Pos.CENTER_LEFT);
        VBox cashFormCard = new VBox(10, cashFormTitle, cashFormRow);
        cashFormCard.setStyle(cardStyle);
        cashFormCard.setPadding(new Insets(15));
        cashFormCard.setMinHeight(80);

        // ── Report buttons (moved from header) ──
        Button viewReportBtn = new Button("عرض التقرير");
        viewReportBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        viewReportBtn.setOnAction(e -> tabPane.getSelectionModel().select(TAB_REPORTS));
        Button exportPdfBtn = new Button("تصدير PDF");
        exportPdfBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        exportPdfBtn.setOnAction(e -> exportDailyPdf());
        HBox reportBtnRow = new HBox(10, viewReportBtn, exportPdfBtn);
        reportBtnRow.setAlignment(Pos.CENTER_LEFT);
        Label reportSectionLabel = new Label("التقارير");
        reportSectionLabel.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        VBox reportBtnCard = new VBox(8, reportSectionLabel, reportBtnRow);
        reportBtnCard.setStyle(cardStyle);
        reportBtnCard.setPadding(new Insets(15));

        // ── Today's Rentals Card ──
        Label rentTitle = new Label("العدد المؤجرة اليوم");
        rentTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        todayRentalsBox.setPadding(new Insets(10, 5, 5, 5));
        todayRentalsBox.setMinHeight(120);
        VBox rentCard = new VBox(10, rentTitle, todayRentalsBox);
        rentCard.setStyle(cardStyle);
        rentCard.setPadding(new Insets(15));
        rentCard.setMinHeight(160);
        VBox.setVgrow(todayRentalsBox, Priority.ALWAYS);

        VBox dashboardContent = new VBox(18, titleFrame, row1, row2, newCustCard, rentCard, cashFormCard, reportBtnCard);
        dashboardContent.setPadding(new Insets(25));
        dashboardContent.setMinHeight(300);

        // ── Page frame ──
        VBox pageFrame = new VBox(dashboardContent);
        pageFrame.setPadding(new Insets(20));
        pageFrame.setStyle("-fx-background-color:#eef2f7;-fx-background-radius:0;" +
            "-fx-border-color:#c5d0dc;" +
            "-fx-border-width:1;" +
            "-fx-border-insets:0;" +
            "-fx-background-insets:0;");

        ScrollPane sp = new ScrollPane(pageFrame);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#eef2f7; -fx-background-color:#eef2f7;");
        return tab("الرئيسية", sp);
    }

    private VBox createDashboardCard(String label, Label valueLabel, String lblStyle, String valStyle, String cardStyle, String accentColor) {
        Label lbl = new Label(label);
        lbl.setStyle(lblStyle);
        valueLabel.setStyle(valStyle + "-fx-text-fill:" + accentColor + ";");
        VBox card = new VBox(6, lbl, valueLabel);
        card.setStyle(cardStyle);
        card.setAlignment(Pos.CENTER);
        return card;
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. STOCK PAGE — Context menu (edit/copy/delete), no buttons
    // ═══════════════════════════════════════════════════════════════
    private Tab stockPage() {
        // Column order: تاريخ الإضافة, الكود, الاسم, الشركة, الصنف, سعر البيع, سعر الجملة, الكمية, العدد, الوحدة, كود المورد, اسم المورد, هاتف المورد, الربح
        String[] headers = {"تاريخ الإضافة", "الكود", "الاسم", "الشركة", "الصنف", "سعر البيع", "سعر الجملة", "الكمية / السعة", "العدد", "الوحدة", "كود المورد", "اسم المورد", "هاتف المورد", "الربح"};
        for (int i = 0; i < headers.length; i++) {
            int x = i;
            TableColumn<ProductRow, String> col = new TableColumn<>(headers[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> { // تاريخ الإضافة — تاريخ فقط، لا وقت
                    String dt = v.getValue().createdAt();
                    if (dt == null || dt.isEmpty() || "—".equals(dt)) yield "—";
                    try {
                        String str = dt.contains(" ") ? dt.split(" ")[0] : dt;
                        if (str.contains("T")) str = str.split("T")[0];
                        if (str.contains("-")) {
                            String[] parts = str.split("-");
                            yield parts[2] + "/" + parts[1] + "/" + parts[0];
                        }
                        yield str;
                    } catch (Exception ignored) { yield dt; }
                }
                case 1 -> v.getValue().code();
                case 2 -> v.getValue().name();
                case 3 -> v.getValue().company();
                case 4 -> v.getValue().category();
                case 5 -> money(v.getValue().sellingPrice());
                case 6 -> money(v.getValue().wholesalePrice());
                case 7 -> { // الكمية
                    ProductRow pr7 = v.getValue();
                    boolean isCountProd = "COUNT".equals(pr7.unit()) && "عدد".equals(pr7.measurementUnit());
                    if (isCountProd) yield "—";
                    yield formatQty(pr7);
                }
                case 8 -> { // العدد
                    ProductRow pr8 = v.getValue();
                    if ("PACKAGE".equals(pr8.unit()) && pr8.packageWeight() > 0) {
                        yield fmt(pr8.quantity());
                    }
                    if ("COUNT".equals(pr8.unit()) && "عدد".equals(pr8.measurementUnit())) {
                        yield pr8.unitCount() > 0 ? fmt(pr8.unitCount()) : "—";
                    }
                    yield "—";
                }
                case 9 -> {
                    String un = v.getValue().unitName();
                    yield (un != null && !un.isEmpty()) ? un : "—";
                }
                case 10 -> { // كود المورد
                    String sc = v.getValue().supplierCode();
                    yield (sc != null && !sc.isEmpty()) ? sc : "";
                }
                case 11 -> { // اسم المورد
                    String sn = v.getValue().supplierName();
                    yield (sn != null && !sn.isEmpty()) ? sn : "";
                }
                case 12 -> { // هاتف المورد
                    String sp = v.getValue().supplierPhone();
                    yield (sp != null && !sp.isEmpty()) ? sp : "";
                }
                default -> money(v.getValue().sellingPrice() - v.getValue().wholesalePrice());
            }));
            if (x == 0) {
                col.setCellFactory(tc -> new TableCell<ProductRow, String>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null || "—".equals(item) ? null : item);
                        setWrapText(true);
                    }
                });
            }
            col.setPrefWidth(i == 2 ? 200 : (i == 9 ? 70 : (i == 8 ? 50 : (i == 0 ? 100 : (i == 10 ? 90 : (i == 11 ? 130 : (i == 12 ? 110 : (i == 1 || i == 7 ? 90 : 110))))))));
            stockTable.getColumns().add(col);
        }
        stockTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(stockTable, "stock", 100, 90, 200, 110, 110, 110, 110, 90, 50, 70, 90, 130, 110, 110);

        stockTable.setRowFactory(tv -> {
            TableRow<ProductRow> row = new TableRow<>();
            // ── Context menu (right-click) ──
            ContextMenu cm = new ContextMenu();
            MenuItem editItem = new MenuItem(" تعديل الأسعار");
            editItem.setOnAction(ev -> showEditProductDialog(row.getItem()));
            MenuItem copyItem = new MenuItem("نسخ الكود");
            copyItem.setOnAction(ev -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(row.getItem().code());
                Clipboard.getSystemClipboard().setContent(cc);
                info("تم نسخ الكود: " + row.getItem().code());
            });
            MenuItem deleteItem = new MenuItem("حذف المنتج");
            deleteItem.setOnAction(ev -> deleteProductWithConfirm(row.getItem()));
            cm.getItems().addAll(editItem, copyItem, new SeparatorMenuItem(), deleteItem);
            row.setContextMenu(cm);
            return row;
        });

        ScrollPane stockScroll = new ScrollPane(stockTable);
        stockScroll.setFitToHeight(true);
        stockScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        stockScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        stockScroll.setStyle("-fx-background:transparent;-fx-background-color:transparent;");

        VBox page = new VBox(13, stockScroll);
        page.setPadding(new Insets(18));
        VBox.setVgrow(stockScroll, Priority.ALWAYS);
        return tab("المنتجات", page);
    }

    private void showEditProductDialog(ProductRow p) {
        Stage dialog = new Stage();
        dialog.setTitle("تعديل أسعار المنتج: " + p.name());
        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color:#f7f9fc;");

        Label title = new Label("تعديل بيانات المنتج");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        Label codeLbl = new Label("الكود: " + p.code());
        Label nameLbl = new Label("الاسم: " + p.name());

        TextField wholesaleField = new TextField(String.valueOf(p.wholesalePrice()));
        wholesaleField.setPromptText("سعر الجملة");
        wholesaleField.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));
        TextField sellingField = new TextField(String.valueOf(p.sellingPrice()));
        sellingField.setPromptText("سعر البيع");
        sellingField.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));

        Button saveBtn = new Button("حفظ التعديلات");
        saveBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;");
        saveBtn.setOnAction(ev -> run(() -> {
            double ws = NumberUtil.parseNumber(wholesaleField.getText());
            double sl = NumberUtil.parseNumber(sellingField.getText());
            service.updateProductPrices(p.id(), ws, sl);
            reload();
            dialog.close();
            info("تم تحديث الأسعار بنجاح");
        }));

        root.getChildren().addAll(title, codeLbl, nameLbl,
            new Label("سعر الجملة:"), wholesaleField,
            new Label("سعر البيع:"), sellingField, saveBtn);
        dialog.setScene(new Scene(root, 350, 300));
        dialog.show();
    }

    private void deleteProductWithConfirm(ProductRow p) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "هل تريد حذف المنتج: " + p.name() + "؟", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
            String result = service.removeProduct(p.id());
            reload();
            info(result);
        }));
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. INCOMING PAGE — Improved design + empty row skip + delete button
    // ═══════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private Tab incomingPage() {
        VBox itemsBox = new VBox(10);
        Label totalLabel = new Label("إجمالي التكلفة: 0.00 ج.م");
        totalLabel.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        // row fields: 0=category, 1=company, 2=name, 3=wholesale, 4=selling, 5=profitLbl,
        // 6=unit, 7=measUnit, 8=unitCount, 9=unitWeight, 10=weightVal, 11=lengthVal, 12=areaVal, 13=row, 14=removeBtn
        List<Object[]> itemFields = new ArrayList<>();

        java.util.function.BiFunction<String, String, String> incomingCase = (unit, meas) -> {
            if (unit == null || meas == null) return "NONE";
            if (isIncomingPackageUnit(unit) && ("لتر".equals(meas) || "ميللي لتر".equals(meas))) return "PACKAGE_VOLUME";
            if ("زجاجة".equals(unit) && "عدد".equals(meas)) return "BOTTLE";
            if ("قطعة".equals(unit) && "عدد".equals(meas)) return "PIECE";
            if ("الوزن".equals(unit) && ("كيلوجرام".equals(meas) || "جرام".equals(meas) || "لتر".equals(meas) || "ميللي لتر".equals(meas))) return "WEIGHT";
            if ("طول".equals(unit) && "متر".equals(meas)) return "LENGTH";
            if ("مقاس".equals(unit) && "متر مسطح".equals(meas)) return "AREA";
            return "UNKNOWN";
        };

        final double[] currentTotalHolder = {0};

        Runnable updateTotal = () -> {
            double total = 0;
            for (Object[] fields : itemFields) {
                try {
                    String unit = ((ComboBox<String>) fields[6]).getValue();
                    String meas = ((ComboBox<String>) fields[7]).getValue();
                    double ws = NumberUtil.parseNumber(((TextField) fields[3]).getText());
                    String c = incomingCase.apply(unit, meas);
                    switch (c) {
                        case "PACKAGE_VOLUME", "PIECE", "BOTTLE" -> {
                            double cnt = NumberUtil.parseNumber(((TextField) fields[8]).getText());
                            total += cnt * ws;
                        }
                        case "WEIGHT" -> {
                            double w = NumberUtil.parseNumber(((TextField) fields[10]).getText());
                            double factor = switch (meas) {
                                case "كيلوجرام", "لتر" -> w;
                                case "جرام", "ميللي لتر" -> w / 1000.0;
                                default -> 0;
                            };
                            total += factor * ws;
                        }
                        case "LENGTH" -> total += NumberUtil.parseNumber(((TextField) fields[11]).getText()) * ws;
                        case "AREA" -> total += NumberUtil.parseNumber(((TextField) fields[12]).getText()) * ws;
                        default -> {}
                    }
                } catch (Exception ignored) {}
            }
            currentTotalHolder[0] = total;
            totalLabel.setText("إجمالي التكلفة: " + money(total));
        };

        // ── Tax checkbox (declared before addItem so updateTotal can reference it) ──
        Label totalWithTaxLabel = new Label("إجمالي شامل الضريبة: 0.00 ج.م");
        totalWithTaxLabel.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#c07030;");

        CheckBox taxCheckBox = new CheckBox("شامل ضريبة القيمة المضافة (14%)");
        taxCheckBox.setStyle("-fx-font-weight:bold;-fx-font-size:13px;");

        // Update totalWithTaxLabel whenever tax checkbox toggles
        taxCheckBox.setOnAction(e -> {
            double base = currentTotalHolder[0];
            totalWithTaxLabel.setText("إجمالي شامل الضريبة: " + money(taxCheckBox.isSelected() ? base * TAX_RATE : base));
        });

        // Auto-update totalWithTaxLabel in real-time when any field changes
        totalLabel.textProperty().addListener((o, a, b) ->
            totalWithTaxLabel.setText("إجمالي شامل الضريبة: " + money(taxCheckBox.isSelected() ? currentTotalHolder[0] * TAX_RATE : currentTotalHolder[0]))
        );

        Runnable addItem = () -> {
            // Live-refresh category list from DB whenever the user interacts with this field
            ComboBox<Category> category = new ComboBox<>();
            category.setCellFactory(lv -> new ListCell<>() {
                @Override protected void updateItem(Category item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.getName());
                }
            });
            category.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(Category c) { return c == null ? "" : c.getName(); }
                @Override public Category fromString(String s) { return null; }
            });
            Runnable refreshCats = () -> {
                List<Category> cats = categoryService.getAllCategories();
                category.setItems(FXCollections.observableArrayList(cats));
            };
            refreshCats.run();
            refreshCallbacks.add(refreshCats);
            category.showingProperty().addListener((o, old, showing) -> { if (showing) refreshCats.run(); });
            category.setPromptText("الصنف *");
            category.setPrefWidth(120);
            category.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

            ComboBox<String> unitType = new ComboBox<>();
            unitType.setPromptText("الوحدة *");
            unitType.setPrefWidth(140);
            unitType.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
            ComboBox<String> measUnit = new ComboBox<>();
            measUnit.setPromptText("وحدة القياس *");
            measUnit.setPrefWidth(140);
            measUnit.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

            TextField company = input("الشركة *");
            company.setPrefWidth(130);
            TextField name = input("اسم المنتج *");
            name.setPrefWidth(160);
            TextField wholesale = numericInput("سعر الجملة *");
            wholesale.setPrefWidth(110);
            TextField selling = numericInput("سعر البيع *");
            selling.setPrefWidth(110);

            TextField unitCount = numericInput("عدد الوحدات *");
            unitCount.setPrefWidth(110);
            TextField unitWeight = numericInput("سعة الوحدة *");
            unitWeight.setPrefWidth(130);
            TextField weightVal = numericInput("الوزن *");
            weightVal.setPrefWidth(110);
            TextField lengthVal = numericInput("الطول بالمتر *");
            lengthVal.setPrefWidth(130);
            TextField areaVal = numericInput("مساحة السطح بالمتر المسطح *");
            areaVal.setPrefWidth(200);

            TextField[] dynamicFields = {unitCount, unitWeight, weightVal, lengthVal, areaVal};

            Label profitLbl = new Label("الربح: 0.00");
            profitLbl.setStyle("-fx-text-fill:#27a068;-fx-font-weight:bold; -fx-font-size:12px;");

            HBox dynamicBox = new HBox(8);
            dynamicBox.setAlignment(Pos.CENTER_RIGHT);

            Runnable refreshDynamicFields = () -> {
                String unit = unitType.getValue();
                String meas = measUnit.getValue();
                String c = incomingCase.apply(unit, meas);
                dynamicBox.getChildren().clear();
                wholesale.setPromptText("سعر الجملة *");
                selling.setPromptText("سعر البيع *");
                switch (c) {
                    case "PACKAGE_VOLUME" -> {
                        dynamicBox.getChildren().addAll(unitCount, unitWeight);
                        unitWeight.setPromptText(switch (meas) {
                            case "لتر" -> "سعة الوحدة (لتر) *";
                            case "ميللي لتر" -> "سعة الوحدة (ميللي لتر) *";
                            default -> "سعة الوحدة *";
                        });
                        wholesale.setPromptText("سعر الجملة *");
                        selling.setPromptText("سعر البيع *");
                    }
                    case "BOTTLE" -> {
                        dynamicBox.getChildren().add(unitCount);
                        unitCount.setPromptText("عدد الزجاجات *");
                        wholesale.setPromptText("سعر الجملة*");
                        selling.setPromptText("سعر البيع*");
                    }
                    case "PIECE" -> {
                        dynamicBox.getChildren().add(unitCount);
                        unitCount.setPromptText("عدد القطع *");
                        wholesale.setPromptText("سعر الجملة*");
                        selling.setPromptText("سعر البيع*");
                    }
                    case "WEIGHT" -> {
                        weightVal.setPromptText(switch (meas) {
                            case "كيلوجرام" -> "الوزن (كيلوجرام) *";
                            case "جرام" -> "الوزن (جرام) *";
                            case "لتر" -> "الوزن (لتر) *";
                            case "ميللي لتر" -> "الوزن (ميللي لتر) *";
                            default -> "الوزن *";
                        });
                        dynamicBox.getChildren().add(weightVal);
                        wholesale.setPromptText(switch (meas) {
                            case "كيلوجرام", "جرام" -> "سعر الجملة *";
                            case "لتر", "ميللي لتر" -> "سعر الجملة*";
                            default -> "سعر الجملة *";
                        });
                        selling.setPromptText(switch (meas) {
                            case "كيلوجرام", "جرام" -> "سعر البيع*";
                            case "لتر", "ميللي لتر" -> "سعر البيع*";
                            default -> "سعر البيع*";
                        });
                    }
                    case "LENGTH" -> {
                        dynamicBox.getChildren().add(lengthVal);
                        wholesale.setPromptText("سعر الجملة*");
                        selling.setPromptText("سعر البيع*");
                    }
                    case "AREA" -> {
                        dynamicBox.getChildren().add(areaVal);
                        wholesale.setPromptText("سعر الجملة*");
                        selling.setPromptText("سعر البيع*");
                    }
                    default -> {}
                }
                updateTotal.run();
            };

            Runnable clearDynamicValues = () -> {
                for (TextField tf : dynamicFields) tf.clear();
                dynamicBox.getChildren().clear();
            };

            category.valueProperty().addListener((o, a, b) -> {
                unitType.getItems().clear();
                measUnit.getItems().clear();
                unitType.setValue(null);
                measUnit.setValue(null);
                clearDynamicValues.run();
                if (b != null) {
                    if (b.getUnits() != null && !b.getUnits().isEmpty()) {
                        for (String u : b.getUnits().split(",")) {
                            String trimmed = u.trim();
                            if (!trimmed.isEmpty()) unitType.getItems().add(trimmed);
                        }
                    }
                    if (b.getMeasurementUnits() != null && !b.getMeasurementUnits().isEmpty()) {
                        for (String m : b.getMeasurementUnits().split(",")) {
                            String trimmed = m.trim();
                            if (!trimmed.isEmpty()) measUnit.getItems().add(trimmed);
                        }
                    }
                }
                refreshDynamicFields.run();
            });

            unitType.valueProperty().addListener((o, a, b) -> { clearDynamicValues.run(); refreshDynamicFields.run(); });
            measUnit.valueProperty().addListener((o, a, b) -> { clearDynamicValues.run(); refreshDynamicFields.run(); });

            Runnable calcProfit = () -> {
                try {
                    double w = NumberUtil.parseNumber(wholesale.getText());
                    double s = NumberUtil.parseNumber(selling.getText());
                    profitLbl.setText("الربح: " + money(Math.max(0, s - w)));
                } catch (Exception ignored) { profitLbl.setText("الربح: 0.00"); }
                updateTotal.run();
            };
            wholesale.textProperty().addListener((o, a, b) -> calcProfit.run());
            selling.textProperty().addListener((o, a, b) -> calcProfit.run());
            for (TextField tf : dynamicFields) tf.textProperty().addListener((o, a, b) -> updateTotal.run());

            HBox row1 = new HBox(8, category, unitType, measUnit, company, name, dynamicBox, wholesale, selling, profitLbl);
            row1.setAlignment(Pos.CENTER_RIGHT);
            VBox row = new VBox(5, row1);
            row.setStyle("-fx-border-color:#d0d5dd;-fx-border-radius:8;-fx-padding:10;" +
                "-fx-background-color:#fafbfd;-fx-background-radius:8;" +
                "-fx-effect:dropshadow(gaussian,#00000008,4,0,0,1);");

            Button removeBtn = new Button("✕");
            removeBtn.setStyle("-fx-background-color:#a73c3c;-fx-text-fill:white;-fx-background-radius:12;" +
                "-fx-font-size:10px;-fx-padding:2 6;-fx-cursor:hand;");
            final Object[] rowFields = new Object[]{category, company, name, wholesale, selling, profitLbl, unitType, measUnit,
                unitCount, unitWeight, weightVal, lengthVal, areaVal, row, removeBtn};
            removeBtn.setOnAction(ev -> {
                itemFields.remove(rowFields);
                itemsBox.getChildren().remove(row);
                updateTotal.run();
            });
            HBox topRow = new HBox(8, row1, new Region(), removeBtn);
            HBox.setHgrow(new Region(), Priority.ALWAYS);
            topRow.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(topRow);
            itemsBox.getChildren().add(row);
            itemFields.add(rowFields);
        };

        // ── Supplier code field (required) with auto-complete ──
        Label supplierSectionLabel = new Label("بيانات المورد");
        supplierSectionLabel.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        TextField supplierCodeField = input("كود المورد *");
        supplierCodeField.setPrefWidth(180);
        Label supplierInfoLbl = new Label("");
        supplierInfoLbl.setStyle("-fx-text-fill:#1b7895; -fx-font-weight:bold;");
        final String[] supplierCodeHolder = {""};
        final String[] supplierNameHolder = {""};

        ListView<InventoryService.Supplier> supplierSuggestions = new ListView<>();
        supplierSuggestions.setPrefHeight(160);
        supplierSuggestions.setPrefWidth(350);
        Popup supplierPopup = new Popup();
        supplierPopup.getContent().add(supplierSuggestions);
        supplierPopup.setAutoHide(true);

        supplierCodeField.textProperty().addListener((obs, o, n) -> {
            if (n == null || n.trim().isEmpty()) {
                supplierInfoLbl.setText("");
                supplierCodeHolder[0] = "";
                supplierNameHolder[0] = "";
                supplierPopup.hide();
                return;
            }
            String txt = n.trim();
            InventoryService.Supplier exact = service.supplierByCode(txt);
            if (exact != null) {
                supplierPopup.hide();
                supplierInfoLbl.setText("✓ " + exact.name() + " | " + exact.phone());
                supplierCodeHolder[0] = exact.code();
                supplierNameHolder[0] = exact.name();
                return;
            }
            List<InventoryService.Supplier> matches = service.suppliersByCodeLike(txt);
            // Filter by selected category (تخصص) from product rows
            String selectedCat = "";
            for (Object[] row : itemFields) {
                Category catObj = ((ComboBox<Category>) row[0]).getValue();
                if (catObj != null) {
                    selectedCat = catObj.getName();
                    break;
                }
            }
            if (!selectedCat.isEmpty()) {
                List<InventoryService.Supplier> filtered = new ArrayList<>();
                for (InventoryService.Supplier sup : matches) {
                    if (sup.specialty() != null) {
                        for (String s : sup.specialty().split(",")) {
                            if (s.trim().equalsIgnoreCase(selectedCat)) {
                                filtered.add(sup);
                                break;
                            }
                        }
                    }
                }
                matches = filtered;
            }
            if (!matches.isEmpty()) {
                supplierSuggestions.setItems(FXCollections.observableArrayList(matches));
                supplierSuggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override protected void updateItem(InventoryService.Supplier item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.code() + " | " + item.name() + " | " + item.phone());
                    }
                });
                if (!supplierPopup.isShowing()) {
                    var bounds = supplierCodeField.localToScreen(supplierCodeField.getBoundsInLocal());
                    if (bounds != null) supplierPopup.show(supplierCodeField, bounds.getMinX(), bounds.getMaxY());
                }
                supplierInfoLbl.setText("");
                supplierCodeHolder[0] = "";
                supplierNameHolder[0] = "";
            } else {
                supplierPopup.hide();
                supplierInfoLbl.setText("✖ لا يوجد مورد بهذا الكود");
                supplierCodeHolder[0] = "";
                supplierNameHolder[0] = "";
            }
        });

        supplierSuggestions.setOnMouseClicked(ev -> {
            InventoryService.Supplier sel = supplierSuggestions.getSelectionModel().getSelectedItem();
            if (sel != null) {
                supplierPopup.hide();
                supplierCodeField.setText(sel.code());
                supplierInfoLbl.setText("✓ " + sel.name() + " | " + sel.phone());
                supplierCodeHolder[0] = sel.code();
                supplierNameHolder[0] = sel.name();
            }
        });

        HBox supplierRow = new HBox(10, supplierCodeField, supplierInfoLbl);
        supplierRow.setAlignment(Pos.CENTER_LEFT);
        VBox supplierBox = new VBox(6, supplierSectionLabel, supplierRow);
        supplierBox.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:15;" +
            "-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");

        refreshCallbacks.clear();
        addItem.run();
        Button addMore = new Button("+ إضافة منتج آخر");
        addMore.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        addMore.setOnAction(e -> addItem.run());
        Button execute = new Button("تنفيذ عملية الشراء");
        execute.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:14px;-fx-padding:10 30;");
        execute.setOnAction(e -> run(() -> {
            List<IncomingItem> items = new ArrayList<>();
            boolean hasAnyData = false;
            for (Object[] fields : itemFields) {
                Category selectedCat = ((ComboBox<Category>) fields[0]).getValue();
                String cat = selectedCat != null ? selectedCat.getName() : null;
                String comp = ((TextField) fields[1]).getText().trim();
                String n = ((TextField) fields[2]).getText().trim();
                String wStr = ((TextField) fields[3]).getText().trim();
                String sStr = ((TextField) fields[4]).getText().trim();
                String unit = ((ComboBox<String>) fields[6]).getValue();
                String meas = ((ComboBox<String>) fields[7]).getValue();

                boolean rowEmpty = cat == null && unit == null && meas == null && comp.isEmpty() && n.isEmpty()
                    && wStr.isEmpty() && sStr.isEmpty() && isIncomingDynamicRowEmpty(fields);
                if (rowEmpty) continue;
                hasAnyData = true;

                if (cat == null || unit == null || meas == null || comp.isEmpty() || n.isEmpty() || wStr.isEmpty() || sStr.isEmpty())
                    throw new IllegalArgumentException("يرجى إدخال جميع البيانات المطلوبة في الصف المُدخل.");

                String c = incomingCase.apply(unit, meas);
                if ("NONE".equals(c) || "UNKNOWN".equals(c))
                    throw new IllegalArgumentException("يرجى اختيار تركيبة صحيحة للوحدة ووحدة القياس.");

                double wholesale = NumberUtil.parseNumber(wStr);
                double selling = NumberUtil.parseNumber(sStr);
                if (wholesale <= 0 || selling <= 0)
                    throw new IllegalArgumentException("يرجى إدخال سعر الجملة وسعر البيع بشكل صحيح.");

                double quantity;
                double pkgW = 0;
                String unitCode;

                switch (c) {
                    case "PACKAGE_VOLUME" -> {
                        String cntStr = ((TextField) fields[8]).getText().trim();
                        String wtStr = ((TextField) fields[9]).getText().trim();
                        if (cntStr.isEmpty() || wtStr.isEmpty())
                            throw new IllegalArgumentException("يرجى إدخال عدد الوحدات وسعة الوحدة.");
                        double cnt = NumberUtil.parseNumber(cntStr);
                        double wt = NumberUtil.parseNumber(wtStr);
                        if (cnt <= 0 || wt <= 0)
                            throw new IllegalArgumentException("يرجى إدخال عدد الوحدات وسعة الوحدة بشكل صحيح.");
                        if (cnt != Math.rint(cnt))
                            throw new IllegalArgumentException("عدد الوحدات يجب أن يكون رقمًا صحيحًا.");
                        quantity = cnt;
                        pkgW = wt;
                        unitCode = "PACKAGE";
                    }
                    case "BOTTLE" -> {
                        String cntStr = ((TextField) fields[8]).getText().trim();
                        if (cntStr.isEmpty()) throw new IllegalArgumentException("يرجى إدخال عدد الزجاجات.");
                        quantity = NumberUtil.parseNumber(cntStr);
                        if (quantity <= 0) throw new IllegalArgumentException("يرجى إدخال عدد الزجاجات بشكل صحيح.");
                        if (quantity != Math.rint(quantity))
                            throw new IllegalArgumentException("عدد الزجاجات يجب أن يكون رقمًا صحيحًا.");
                        unitCode = "COUNT";
                    }
                    case "PIECE" -> {
                        String cntStr = ((TextField) fields[8]).getText().trim();
                        if (cntStr.isEmpty()) throw new IllegalArgumentException("يرجى إدخال عدد الوحدات.");
                        quantity = NumberUtil.parseNumber(cntStr);
                        if (quantity <= 0) throw new IllegalArgumentException("يرجى إدخال عدد الوحدات بشكل صحيح.");
                        if (quantity != Math.rint(quantity))
                            throw new IllegalArgumentException("عدد الوحدات يجب أن يكون رقمًا صحيحًا.");
                        unitCode = "COUNT";
                    }
                    case "WEIGHT" -> {
                        String wtStr = ((TextField) fields[10]).getText().trim();
                        if (wtStr.isEmpty()) throw new IllegalArgumentException("يرجى إدخال الوزن.");
                        double w = NumberUtil.parseNumber(wtStr);
                        if (w <= 0) throw new IllegalArgumentException("يرجى إدخال الوزن بشكل صحيح.");
                        quantity = switch (meas) {
                            case "كيلوجرام", "لتر" -> w * 1000.0;
                            case "جرام", "ميللي لتر" -> w;
                            default -> 0;
                        };
                        unitCode = "WEIGHT";
                    }
                    case "LENGTH" -> {
                        String lenStr = ((TextField) fields[11]).getText().trim();
                        if (lenStr.isEmpty()) throw new IllegalArgumentException("يرجى إدخال الطول بالمتر.");
                        quantity = NumberUtil.parseNumber(lenStr);
                        if (quantity <= 0) throw new IllegalArgumentException("يرجى إدخال الطول بالمتر بشكل صحيح.");
                        unitCode = "COUNT";
                    }
                    case "AREA" -> {
                        String areaStr = ((TextField) fields[12]).getText().trim();
                        if (areaStr.isEmpty()) throw new IllegalArgumentException("يرجى إدخال مساحة السطح بالمتر المسطح.");
                        quantity = NumberUtil.parseNumber(areaStr);
                        if (quantity <= 0) throw new IllegalArgumentException("يرجى إدخال مساحة السطح بشكل صحيح.");
                        unitCode = "COUNT";
                    }
                    default -> throw new IllegalArgumentException("يرجى اختيار تركيبة صحيحة للوحدة ووحدة القياس.");
                }

                double itemTax = 0;
                if (taxCheckBox.isSelected()) {
                    double baseAmount = unitCode.equals("WEIGHT") ? (quantity / 1000.0) * wholesale : quantity * wholesale;
                    itemTax = baseAmount * (TAX_RATE - 1); // 14% of purchase amount
                }
                items.add(new IncomingItem(comp, cat, n, quantity, wholesale, selling, unitCode, pkgW, itemTax, meas, unit, supplierCodeHolder[0], supplierNameHolder[0]));
            }
            if (!hasAnyData) throw new IllegalArgumentException("أضف منتجًا واحدًا على الأقل.");
            String confirmTotal = taxCheckBox.isSelected() ? totalWithTaxLabel.getText() : totalLabel.getText();
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "تأكيد عملية الشراء بإجمالي: " + confirmTotal + "؟", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> {
                try {
                    service.addIncoming(items);
                    itemsBox.getChildren().clear();
                    itemFields.clear();
                    refreshCallbacks.clear();
                    addItem.run();
                    currentTotalHolder[0] = 0;
                    totalLabel.setText("إجمالي التكلفة: 0.00 ج.م");
                    reload();
                    supplierCodeField.clear();
                    supplierInfoLbl.setText("");
                    supplierCodeHolder[0] = "";
                    supplierNameHolder[0] = "";
                    info("تمت عملية الشراء بنجاح");
                } catch (Exception ex) { error(ex.getMessage()); }
            });
        }));

        Label pageTitle = new Label("إضافة وارد - يجب أن تكون قد أدخلت كود المورد ");
        pageTitle.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        // Clean up any old references (moved above addItem)

        VBox page = new VBox(15, supplierBox, pageTitle, itemsBox, addMore, new Separator(), totalLabel, taxCheckBox, totalWithTaxLabel, execute);
        page.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");
        return tab("إضافة وارد", sp);
    }

    private static boolean isIncomingPackageUnit(String unit) {
        return "بستلة".equals(unit) || "عبوة".equals(unit) || "زجاجة".equals(unit)
            || "جالون".equals(unit) || "جالون دوبل".equals(unit) || "جركن".equals(unit);
    }

    private static boolean isIncomingDynamicRowEmpty(Object[] fields) {
        for (int i = 8; i <= 12; i++) {
            if (!((TextField) fields[i]).getText().trim().isEmpty()) return false;
        }
        return true;
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. SALES PAGE — Customer code field per row
    // ═══════════════════════════════════════════════════════════════
    private Tab salesPage() {
        VBox itemsBox = new VBox(10);
        Label totalSaleLabel = new Label("إجمالي البيع: 0.00 ج.م");
        Label totalProfitLabel = new Label("إجمالي الربح: 0.00 ج.م");
        totalSaleLabel.setStyle("-fx-font-size:15px;-fx-font-weight:bold;");
        totalProfitLabel.setStyle("-fx-font-size:15px;");

        List<Object[]> saleItems = new ArrayList<>();
        final double[] totalSaleHolder = {0};

        Runnable updateTotals = () -> {
            double totalSale = 0, totalProfit = 0;
            for (Object[] item : saleItems) {
                try {
                    double qty = NumberUtil.parseNumber(((TextField) item[2]).getText());
                    double sell = NumberUtil.parseNumber(((TextField) item[3]).getText());
                    double ws = (double) item[5];
                    String ut = (String) item[7];
                    if ("WEIGHT".equals(ut)) { totalSale += (qty / 1000.0) * sell; totalProfit += (qty / 1000.0) * Math.max(0, sell - ws); }
                    else { totalSale += qty * sell; totalProfit += qty * Math.max(0, sell - ws); }
                } catch (Exception ignored) {}
            }
            totalSaleHolder[0] = totalSale;
            totalSaleLabel.setText("إجمالي البيع: " + money(totalSale));
            totalProfitLabel.setText("إجمالي الربح: " + money(totalProfit));
        };

        // Customer code field (optional — auto-complete + case-insensitive search)
        Label custLabel = new Label("كود العميل:");
        custLabel.setStyle("-fx-font-weight:bold;-fx-text-fill:#183b57;");
        TextField customerCodeField = input("كود العميل");
        customerCodeField.setPrefWidth(180);
        Label custInfoLabel = new Label("");
        custInfoLabel.setStyle("-fx-text-fill:#1b7895; -fx-font-weight:bold;");

        // ── Auto-complete for customer code (unified SearchEngine) ──
        SearchEngine.AutoCompleteResult<Customer> custAc = SearchEngine.attachAutoComplete(
            customerCodeField,
            query -> {
                // Try exact match first
                Customer exact = service.customerByCode(query);
                if (exact != null) {
                    custInfoLabel.setText("✓ " + exact.name() + " | " + exact.phone());
                    return java.util.Collections.emptyList();
                }
                return service.customersByCodeLike(query);
            },
            c -> c.code() + " | " + c.name() + " | " + c.phone(),
            sel -> {
                customerCodeField.setText(sel.code());
                custInfoLabel.setText("✓ " + sel.name() + " | " + sel.phone());
            });

        Runnable addSaleItem = () -> {
            TextField code = input("كود المنتج");
            code.setPrefWidth(130);
            Label infoLbl = new Label("أدخل كود المنتج");
            Label unitLbl = new Label("");
            unitLbl.setStyle("-fx-text-fill:#1b7895;-fx-font-weight:bold;");

            // ── Product Details Card (hidden initially) ──
            String detailCardStyle = "-fx-background-color:#f0f4f9;-fx-background-radius:8;-fx-padding:12 14;" +
                "-fx-border-color:#d0d5dd;-fx-border-radius:8;";
            String dtStyle = "-fx-font-size:10px;-fx-text-fill:#888;-fx-font-weight:bold;";
            String dvStyle = "-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#333;";

            VBox detailsCard = new VBox(6);
            detailsCard.setStyle(detailCardStyle);
            detailsCard.setVisible(false);
            detailsCard.setManaged(false);

            // Create all detail value labels
            Label dvCode = new Label("—"); dvCode.setStyle(dvStyle);
            Label dvName = new Label("—"); dvName.setStyle(dvStyle);
            Label dvCompany = new Label("—"); dvCompany.setStyle(dvStyle);
            Label dvCategory = new Label("—"); dvCategory.setStyle(dvStyle);
            Label dvUnit = new Label("—"); dvUnit.setStyle(dvStyle);
            Label dvMeas = new Label("—"); dvMeas.setStyle(dvStyle);
            Label dvQty = new Label("—"); dvQty.setStyle(dvStyle);
            Label dvCount = new Label("—"); dvCount.setStyle(dvStyle);
            Label dvSell = new Label("—"); dvSell.setStyle(dvStyle);
            Label dvWS = new Label("—"); dvWS.setStyle(dvStyle);
            Label dvProfit = new Label("—"); dvProfit.setStyle(dvStyle);

            // Helper to create a field VBox (title + value)
            java.util.function.BiFunction<String, Label, VBox> df = (t, v) -> {
                Label tl = new Label(t);
                tl.setStyle(dtStyle);
                VBox vb = new VBox(2, tl, v);
                vb.setMinWidth(130);
                return vb;
            };

            // Build the card layout in pairs — matches products page columns
            HBox cardRow1 = new HBox(30, df.apply("الكود", dvCode), df.apply("الاسم", dvName));
            HBox cardRow2 = new HBox(30, df.apply("الشركة", dvCompany), df.apply("الصنف", dvCategory));
            HBox cardRow3 = new HBox(30, df.apply("الوحدة", dvUnit), df.apply("وحدة القياس", dvMeas));
            HBox cardRow4 = new HBox(30, df.apply("الكمية / السعة", dvQty), df.apply("العدد", dvCount));
            HBox cardRow5 = new HBox(30, df.apply("سعر البيع", dvSell), df.apply("سعر الشراء", dvWS));
            HBox cardRow6 = new HBox(30, df.apply("الربح", dvProfit));

            detailsCard.getChildren().addAll(cardRow1, cardRow2, cardRow3, cardRow4, cardRow5, cardRow6);

            TextField qty = numericInput("الكمية / السعة");
            qty.setPrefWidth(110);
            TextField sellPrice = numericInput("سعر البيع");
            sellPrice.setPrefWidth(110);
            Label profitLbl = new Label("الربح: —");
            profitLbl.setStyle("-fx-text-fill:#27a068;-fx-font-weight:bold;-fx-font-size:12px;");
            TextField finalPriceField = numericInput("السعر النهائي");
            finalPriceField.setPrefWidth(120);
            finalPriceField.setVisible(false); finalPriceField.setManaged(false);
            final boolean[] updating = {false};
            final double[] wsHolder = {0};
            final boolean[] found = {false};
            final String[] unitHolder = {"COUNT"};

            ListView<ProductRow> codeSuggestions = new ListView<>();
            codeSuggestions.setPrefHeight(150); codeSuggestions.setPrefWidth(350);
            Popup codePopup = new Popup();
            codePopup.getContent().add(codeSuggestions);
            codePopup.setAutoHide(true);

            Runnable fillProduct = () -> {
                String txt = code.getText().trim();
                if (txt.isEmpty()) { 
                    infoLbl.setText("أدخل كود المنتج"); unitLbl.setText(""); 
                    detailsCard.setVisible(false); detailsCard.setManaged(false);
                    found[0] = false; finalPriceField.setVisible(false); finalPriceField.setManaged(false); 
                    return; 
                }
                List<ProductRow> matches = service.productsForSale(txt);
                ProductRow exact = matches.stream().filter(p -> p.code().equalsIgnoreCase(txt)).findFirst().orElse(null);
                if (exact != null) {
                    codePopup.hide(); 
                    //infoLbl.setText(exact.name() + " | متاح: " + formatQty(exact));
                    String exactUn = exact.unitName() != null && !exact.unitName().isEmpty() ? exact.unitName() : "—";
                    unitLbl.setText(exactUn); 
                    sellPrice.setText(String.valueOf(exact.sellingPrice()));
                    wsHolder[0] = exact.wholesalePrice(); unitHolder[0] = exact.unit(); found[0] = true;
                    profitLbl.setText("الربح: " + money(Math.max(0, exact.sellingPrice() - exact.wholesalePrice())));

                    // ── Update product details card ──
                    dvCode.setText(exact.code());
                    dvName.setText(exact.name());
                    dvCompany.setText(exact.company());
                    dvCategory.setText(exact.category());
                    dvUnit.setText(exactUn);
                    dvMeas.setText(exact.measurementUnit() != null && !exact.measurementUnit().isEmpty() ? exact.measurementUnit() : "—");
                    // الكمية — نفس منطق صفحة المنتجات
                    boolean isCountProd = "COUNT".equals(exact.unit()) && "عدد".equals(exact.measurementUnit());
                    dvQty.setText(isCountProd ? "—" : formatQty(exact));
                    // العدد — نفس منطق صفحة المنتجات
                    if ("PACKAGE".equals(exact.unit()) && exact.packageWeight() > 0) {
                        dvCount.setText(fmt(exact.quantity()));
                    } else if (isCountProd) {
                        dvCount.setText(exact.unitCount() > 0 ? fmt(exact.unitCount()) : "—");
                    } else {
                        dvCount.setText("—");
                    }
                    dvSell.setText(money(exact.sellingPrice()));
                    dvWS.setText(money(exact.wholesalePrice()));
                    dvProfit.setText(money(Math.max(0, exact.sellingPrice() - exact.wholesalePrice())));
                    detailsCard.setVisible(true); detailsCard.setManaged(true);

                    if ("WEIGHT".equals(exact.unit())) { qty.setPromptText("الكمية بالجرام"); finalPriceField.setVisible(true); finalPriceField.setManaged(true); }
                    else if ("PACKAGE".equals(exact.unit())) { qty.setPromptText("عدد العبوات"); finalPriceField.setVisible(false); finalPriceField.setManaged(false); }
                    else { qty.setPromptText("الكمية / السعة"); finalPriceField.setVisible(false); finalPriceField.setManaged(false); }
                    for (Object[] si : saleItems) { if (si[0] == code) { si[5] = wsHolder[0]; si[7] = unitHolder[0]; break; } }
                    updateTotals.run();
                } else if (!matches.isEmpty()) {
                    codeSuggestions.setItems(FXCollections.observableArrayList(matches));
                    codeSuggestions.setCellFactory(lv -> new ListCell<>() { @Override protected void updateItem(ProductRow item, boolean empty) { super.updateItem(item, empty); setText(empty || item == null ? null : item.code() + " | " + item.name() + " | " + item.company()); } });
                    if (!codePopup.isShowing()) { var bounds = code.localToScreen(code.getBoundsInLocal()); if (bounds != null) codePopup.show(code, bounds.getMinX(), bounds.getMaxY()); }
                    found[0] = false; finalPriceField.setVisible(false); finalPriceField.setManaged(false);
                    detailsCard.setVisible(false); detailsCard.setManaged(false);
                } else { 
                    codePopup.hide(); 
                    infoLbl.setText("هذا المنتج غير موجود في الأصناف، يرجى إضافته أولًا من صفحة الأصناف."); 
                    unitLbl.setText(""); 
                    detailsCard.setVisible(false); detailsCard.setManaged(false);
                    found[0] = false; finalPriceField.setVisible(false); finalPriceField.setManaged(false); 
                }
            };
            code.textProperty().addListener((obs, o, n) -> fillProduct.run());
            codeSuggestions.setOnMouseClicked(ev -> { ProductRow sel = codeSuggestions.getSelectionModel().getSelectedItem(); if (sel != null) { codePopup.hide(); code.setText(sel.code()); } });

            Runnable calcProfit = () -> {
                try { double s = NumberUtil.parseNumber(sellPrice.getText()); double profit = Math.max(0, s - wsHolder[0]); profitLbl.setText("الربح: " + money(profit));
                    if ("WEIGHT".equals(unitHolder[0]) && !updating[0]) { try { double q = NumberUtil.parseNumber(qty.getText()); updating[0] = true; finalPriceField.setText(String.format("%.2f", q / 1000.0 * s)); } catch (Exception ignored2) {} updating[0] = false; }
                } catch (Exception ignored) {}
                updateTotals.run();
            };
            sellPrice.textProperty().addListener((o, a, b) -> calcProfit.run());
            qty.textProperty().addListener((o, a, b) -> updateTotals.run());
            qty.textProperty().addListener((obs, old, val) -> {
                if (updating[0] || !"WEIGHT".equals(unitHolder[0])) return;
                try { double q = NumberUtil.parseNumber(val); double sp = NumberUtil.parseNumber(sellPrice.getText()); updating[0] = true; finalPriceField.setText(String.format("%.2f", q / 1000.0 * sp)); } catch (Exception ignored) {}
                updating[0] = false;
            });
            finalPriceField.textProperty().addListener((obs, old, val) -> {
                if (updating[0] || !"WEIGHT".equals(unitHolder[0])) return;
                try { double fp = NumberUtil.parseNumber(val); double sp = NumberUtil.parseNumber(sellPrice.getText()); if (sp > 0) { updating[0] = true; qty.setText(String.format("%.2f", fp / sp * 1000)); } } catch (Exception ignored) {}
                updating[0] = false;
            });

            VBox row = new VBox(5, new HBox(8, code, infoLbl, unitLbl), detailsCard, new HBox(8, qty, finalPriceField, sellPrice, profitLbl));
            row.setStyle("-fx-border-color:#d0d5dd;-fx-border-radius:8;-fx-padding:10;" +
                "-fx-background-color:#fafbfd;-fx-background-radius:8;" +
                "-fx-effect:dropshadow(gaussian,#00000008,4,0,0,1);");

            // Remove button for this row
            Button removeBtn = new Button("✕");
            removeBtn.setStyle("-fx-background-color:#a73c3c;-fx-text-fill:white;-fx-background-radius:12;" +
                "-fx-font-size:10px;-fx-padding:2 6;-fx-cursor:hand;");
            final VBox rowRef = row;
            removeBtn.setOnAction(ev -> {
                saleItems.removeIf(item -> item[6] == rowRef);
                itemsBox.getChildren().remove(rowRef);
                updateTotals.run();
            });
            // First row: code field with consistent width + remove button
            code.setPrefWidth(180);
            HBox codeRow = new HBox(8, code, new Region(), removeBtn);
            HBox.setHgrow(new Region(), Priority.ALWAYS);
            codeRow.setAlignment(Pos.CENTER_LEFT);
            // Info row: shows product info and unit
            HBox infoRow = new HBox(8, infoLbl, unitLbl);
            infoRow.setAlignment(Pos.CENTER_RIGHT);
            row.getChildren().set(0, codeRow);
            row.getChildren().add(1, infoRow);

            itemsBox.getChildren().add(row);
            saleItems.add(new Object[]{code, infoLbl, qty, sellPrice, profitLbl, wsHolder[0], row, unitHolder[0], finalPriceField});
        };

        addSaleItem.run();
        Button addMore = new Button("+ إضافة منتج للبيع");
        addMore.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        addMore.setOnAction(e -> addSaleItem.run());

        // Tax checkbox (must be declared before executeSale lambda)
        CheckBox taxCheckBox = new CheckBox("إضافة ضريبة 14%");
        taxCheckBox.setStyle("-fx-font-weight:bold;-fx-font-size:13px;");
        Label totalWithTaxLabel = new Label("الإجمالي شامل الضريبة: 0.00 ج.م");
        totalWithTaxLabel.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#c07030;");
        totalWithTaxLabel.setVisible(false); totalWithTaxLabel.setManaged(false);
        taxCheckBox.setOnAction(e -> {
            if (taxCheckBox.isSelected()) {
                totalWithTaxLabel.setText("الإجمالي شامل الضريبة: " + money(totalSaleHolder[0] * TAX_RATE));
                totalWithTaxLabel.setVisible(true); totalWithTaxLabel.setManaged(true);
            } else {
                totalWithTaxLabel.setVisible(false); totalWithTaxLabel.setManaged(false);
            }
        });

        Button executeSale = new Button("تنفيذ البيع");
        executeSale.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:14px;-fx-padding:10 30;");
        executeSale.setOnAction(e -> run(() -> {
            List<SaleItem> items = new ArrayList<>();
            boolean hasAnyData = false;
            for (Object[] item : saleItems) {
                String codeText = ((TextField) item[0]).getText().trim();
                String qtyText = ((TextField) item[2]).getText().trim();
                String priceText = ((TextField) item[3]).getText().trim();
                // Skip completely empty rows
                if (codeText.isEmpty() && qtyText.isEmpty() && priceText.isEmpty()) continue;
                hasAnyData = true;
                if (codeText.isEmpty() || qtyText.isEmpty() || priceText.isEmpty()) throw new IllegalArgumentException("يرجى إدخال جميع البيانات في الصف المُدخل.");
                ProductRow p = service.productByCode(codeText);
                double sellPrice = NumberUtil.parseNumber(priceText);
                double qty = NumberUtil.parseNumber(qtyText);
                String ut = p.unit();
                double saleBase = "WEIGHT".equals(ut) ? (qty / 1000.0) * sellPrice : qty * sellPrice;
                double itemTax = taxCheckBox.isSelected() ? saleBase * (TAX_RATE - 1) : 0;
                items.add(new SaleItem(p.id(), p.name(), qty, sellPrice, "", ut, itemTax));
            }
            if (!hasAnyData) throw new IllegalArgumentException("أضف منتجًا واحدًا على الأقل.");
            String custCode = customerCodeField.getText().trim();
            Customer cust = null;
            if (!custCode.isEmpty()) {
                cust = service.customerByCode(custCode);
                if (cust == null) {
                    throw new IllegalArgumentException("لا يوجد عميل مسجل بهذا الكود: " + custCode);
                }
            }
            service.sellMultipleWithCustomer(items, cust != null ? cust.code() : "", cust != null ? cust.name() : "");
            itemsBox.getChildren().clear(); saleItems.clear(); addSaleItem.run();
            totalSaleHolder[0] = 0;
            totalSaleLabel.setText("إجمالي البيع: 0.00 ج.م"); totalProfitLabel.setText("إجمالي الربح: 0.00 ج.م");
            customerCodeField.clear(); custInfoLabel.setText("");
            reload(); info("تمت عملية البيع بنجاح");
        }));

        Runnable updateTaxTotal = () -> {
            if (taxCheckBox.isSelected()) {
                totalWithTaxLabel.setText("الإجمالي شامل الضريبة: " + money(totalSaleHolder[0] * TAX_RATE));
                totalWithTaxLabel.setVisible(true); totalWithTaxLabel.setManaged(true);
            } else {
                totalWithTaxLabel.setVisible(false); totalWithTaxLabel.setManaged(false);
            }
        };
        totalSaleLabel.textProperty().addListener((o, a, b) -> updateTaxTotal.run());

        Label salesTitle = new Label("تسجيل عملية بيع — أدخل كود المنتج");
        salesTitle.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        VBox page = new VBox(15, salesTitle,
            new HBox(8, custLabel, customerCodeField, custInfoLabel),
            itemsBox, addMore, new Separator(), totalSaleLabel, totalProfitLabel, taxCheckBox, totalWithTaxLabel, executeSale);
        page.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(page); sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");
        return tab("البيع", sp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. RETURNS PAGE — Export PDF button added
    // ═══════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private Tab returnsPage() {
        TableColumn<SaleRow, String>[] cols = new TableColumn[]{
            column("التاريخ", SaleRow::dateTime),
            column("رقم العملية", (SaleRow x) -> String.valueOf(x.transactionId())),
            column("الكود", SaleRow::code),
            column("المنتج", SaleRow::product),
            column("المباع", (SaleRow x) -> fmt(x.quantity())),
            column("تم إرجاعه", (SaleRow x) -> fmt(x.returnedQuantity())),
            column("السعر", (SaleRow x) -> money(x.price())),
            column("العميل", (SaleRow x) -> x.customerName() != null && !x.customerName().isEmpty() ? x.customerName() : "-")
        };
        salesTable.getColumns().addAll(cols);
        double[] saleWidths = {150, 80, 80, 200, 80, 80, 100, 130};
        for (int i = 0; i < cols.length && i < saleWidths.length; i++) cols[i].setPrefWidth(saleWidths[i]);
        salesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(salesTable, "sales", 150, 80, 80, 200, 80, 80, 100, 130);
        salesTable.setPlaceholder(new Label("لا توجد عمليات بيع قابلة للمرتجع."));

        Label picked = new Label("اختر عملية بيع من الجدول أولاً.");
        TextField returned = numericInput("الكمية المرتجعة");
        TextField reason = input("ملاحظات (اختياري)");
        Button apply = new Button("تأكيد المرتجع");
        apply.setOnAction(e -> run(() -> {
            var sale = salesTable.getSelectionModel().getSelectedItem();
            if (sale == null) throw new IllegalArgumentException("اختر عملية بيع من الجدول");
            String note = reason.getText() != null ? reason.getText().trim() : "";
            // Look up customer code from the original sale for atomic return tracking
            String custCode = service.getCustomerCodeForTransaction(sale.transactionId());
            service.returnSaleWithCustomer(sale.transactionId(), num(returned), note, custCode != null ? custCode : "");
            returned.clear(); reason.clear();
            picked.setText("تم تسجيل المرتجع بنجاح.");
            reload();
        }));

        salesTable.getSelectionModel().selectedItemProperty().addListener((o, old, sale) -> {
            if (sale != null) picked.setText("عملية #" + sale.transactionId() + " | " + sale.product() + " | المتبقي: " + fmt(sale.quantity() - sale.returnedQuantity()));
        });

        Button exportReturnsPdf = new Button("تصدير تقرير المرتجعات PDF");
        exportReturnsPdf.setStyle("-fx-background-color:#7050a0;-fx-background-radius:5;-fx-text-fill:white;");
        exportReturnsPdf.setOnAction(e -> {
            FileChooser fc = pdfChooser("مرتجعات_اليوم.pdf");
            File file = fc.showSaveDialog(stage);
            if (file != null) run(() -> { reports.exportReturnsPdf(file.toPath()); info("تم تصدير تقرير المرتجعات بنجاح"); });
        });

        VBox page = new VBox(12, new Label("المرتجعات — اختر عملية البيع الأصلية"), salesTable, picked, returned, reason,
            new HBox(10, apply, exportReturnsPdf));
        page.setPadding(new Insets(18));
        VBox.setVgrow(salesTable, Priority.ALWAYS);
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:transparent;-fx-background-color:transparent;");
        return tab("المرتجعات", sp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. CASH PAGE — Professional redesign + PDF export
    // ═══════════════════════════════════════════════════════════════
    private Tab cashPage() {
        // ── Professional treasury cards ──
        String cashCardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:18 22;" +
            "-fx-effect:dropshadow(gaussian,#00000012,8,0,0,2);";
        String cashTitleStyle = "-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;";
        String cashValStyle = "-fx-font-size:22px;-fx-font-weight:bold;";

        // ── Today's summary card ──
        VBox todayCard = new VBox(10);
        todayCard.setStyle(cashCardStyle);
        todayCard.setMinHeight(130);
        Label todayTitle = new Label("ملخص اليوم");
        todayTitle.setStyle(cashTitleStyle);
        treasuryToday.setStyle(cashValStyle + "-fx-text-fill:#1b7895;");
        HBox todayStats = new HBox(20);
        todayStats.setAlignment(Pos.CENTER_LEFT);
        todayStats.getChildren().add(treasuryToday);
        todayCard.getChildren().addAll(todayTitle, todayStats);

        // ── Total Profit Card ──
        totalProfitLabel.setStyle(cashValStyle + "-fx-text-fill:#27a068;");
        VBox profitCard = new VBox(10);
        profitCard.setStyle(cashCardStyle);
        profitCard.setMinHeight(100);
        Label profitTitle = new Label("إجمالي الأرباح");
        profitTitle.setStyle(cashTitleStyle);
        HBox profitStats = new HBox(20);
        profitStats.setAlignment(Pos.CENTER_LEFT);
        profitStats.getChildren().add(totalProfitLabel);
        profitCard.getChildren().addAll(profitTitle, profitStats);

        // ── Operation form card ──
        TextField personName = input("المودع / الساحب *"); personName.setPrefWidth(200);
        TextField amount = numericInput("المبلغ"); amount.setPrefWidth(200);
        TextField note = input("ملاحظات"); note.setPrefWidth(250);
        Button deposit = new Button("إيداع");
        deposit.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:8 20;");
        Button withdrawal = new Button("سحب");
        withdrawal.setStyle("-fx-background-color:#a73c3c;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:8 20;");
        deposit.setOnAction(e -> doCash("DEPOSIT", personName, amount, note));
        withdrawal.setOnAction(e -> doCash("WITHDRAWAL", personName, amount, note));
        HBox opRow = new HBox(10, personName, amount, note, deposit, withdrawal);
        opRow.setAlignment(Pos.CENTER_LEFT);

        VBox opCard = new VBox(12);
        opCard.setStyle(cashCardStyle);
        Label opTitle = new Label("إجراء عملية خزينة جديدة");
        opTitle.setStyle(cashTitleStyle);
        opCard.getChildren().addAll(opTitle, opRow);

        // ── PDF export button ──
        Button exportCashPdf = new Button("تصدير تقرير الخزينة PDF");
        exportCashPdf.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:10 25;");
        exportCashPdf.setOnAction(e -> {
            FileChooser fc = pdfChooser("تقرير_الخزينة.pdf");
            File file = fc.showSaveDialog(stage);
            if (file != null) run(() -> { reports.exportCashOpsPdf(file.toPath()); info("تم تصدير تقرير الخزينة بنجاح"); });
        });
        HBox pdfRow = new HBox(exportCashPdf);
        pdfRow.setAlignment(Pos.CENTER_LEFT);

        // ── Cash operations table ──
        cashLedgerTable.getColumns().clear();
        cashLedgerTable.setStyle("-fx-background-radius:10;");
        String[] clHeaders = {"الوقت", "نوع", "المبلغ", "المودع / الساحب", "ملاحظات"};
        for (int i = 0; i < clHeaders.length; i++) {
            int x = i;
            TableColumn<InventoryService.CashLedgerRow, String> col = new TableColumn<>(clHeaders[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> v.getValue().time();
                case 1 -> "DEPOSIT".equals(v.getValue().type()) ? "إيداع" : "سحب";
                case 2 -> money(v.getValue().amount());
                case 3 -> v.getValue().personName() != null && !v.getValue().personName().isEmpty() ? v.getValue().personName() : "—";
                default -> v.getValue().notes() != null && !v.getValue().notes().isEmpty() ? v.getValue().notes() : "—";
            }));
            col.setPrefWidth(switch (x) {
                case 0 -> 130;
                case 1 -> 80;
                case 2 -> 110;
                case 3 -> 150;
                default -> 200;
            });
            cashLedgerTable.getColumns().add(col);
        }
        cashLedgerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(cashLedgerTable, "cash_ledger", 130, 80, 110, 150, 200);

        // Refresh cash ledger table data
        List<InventoryService.CashLedgerRow> clRows = service.getAllCashLedger();
        cashLedgerTable.setItems(FXCollections.observableArrayList(clRows));

        VBox page = new VBox(15, todayCard, profitCard, opCard, pdfRow, cashLedgerTable);
        page.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(page); sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");
        return tab("الخزينة", sp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. TRANSACTIONS PAGE — Customer code & name columns
    // ═══════════════════════════════════════════════════════════════
    private Tab transactionsPage() {
        String[] headers = {"الوقت", "النوع", "المنتج", "الكمية / السعة", "العدد", "كود العميل", "اسم العميل", "كود المورد", "اسم المورد", "السعر", "الربح", "ملاحظات"};
        for (int i = 0; i < headers.length; i++) {
            int x = i;
            TableColumn<DailyTransaction, String> col = new TableColumn<>(headers[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> v.getValue().time();
                case 1 -> v.getValue().type();
                case 2 -> v.getValue().productName() != null ? v.getValue().productName() : "-";
                case 3 -> { // الكمية / السعة — سعة الوحدة (packageWeight) من شاشة إضافة الوارد
                    double pw = v.getValue().packageWeight();
                    String mu = v.getValue().measurementUnit() != null ? v.getValue().measurementUnit().trim() : "";
                    String ut = v.getValue().unitType();
                    if ("PACKAGE".equals(ut) && pw > 0) {
                        if (!mu.isEmpty()) {
                            yield switch (mu) {
                                case "لتر" -> fmt(pw) + " لتر";
                                case "ميللي لتر" -> String.format("%.0f ميللي لتر", pw);
                                default -> fmt(pw) + " " + mu;
                            };
                        }
                        yield fmt(pw);
                    }
                    yield "—";
                }
                case 4 -> { // العدد — يعرض فقط للمنتجات التي تحسب بالعدد
                    String ut = v.getValue().unitType();
                    String mu = v.getValue().measurementUnit() != null ? v.getValue().measurementUnit().trim() : "";
                    double qty = v.getValue().quantity();
                    if ("COUNT".equals(ut) && "عدد".equals(mu) && qty > 0) { yield fmt(qty); }
                    if ("PACKAGE".equals(ut) && qty > 0) { yield fmt(qty); }
                    yield "—";
                }
                case 5 -> {
                    String cc = v.getValue().customerCode();
                    yield (cc != null && !cc.isEmpty()) ? cc : "";
                }
                case 6 -> {
                    String cn = v.getValue().customerName();
                    yield (cn != null && !cn.isEmpty()) ? cn : "";
                }
                case 7 -> {
                    String sc = v.getValue().supplierCode();
                    yield (sc != null && !sc.isEmpty()) ? sc : "";
                }
                case 8 -> {
                    String sn = v.getValue().supplierName();
                    yield (sn != null && !sn.isEmpty()) ? sn : "";
                }
                case 9 -> money(v.getValue().price());
                case 10 -> money(v.getValue().profit());
                default -> v.getValue().notes() != null ? v.getValue().notes() : "";
            }));
            col.setPrefWidth(i == 2 ? 160 : (i == 4 ? 50 : (i == 5 ? 90 : (i == 6 ? 130 : (i == 7 ? 90 : (i == 8 ? 130 : (i == 9 || i == 10 || i == 11 ? 100 : 80)))))));
            txTable.getColumns().add(col);
        }
        txTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        txTable.setPlaceholder(new Label("لا توجد معاملات اليوم."));

        // Title
        Label txTitle = new Label("معاملات اليوم");
        txTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        // Table directly in VBox with Vgrow — fills all available height
        VBox page = new VBox(6, txTitle, txTable);
        page.setPadding(new Insets(10, 8, 8, 8));
        VBox.setVgrow(txTable, Priority.ALWAYS);

        // Only one ScrollPane — wraps the whole page
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setFitToHeight(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.setStyle("-fx-background:transparent;-fx-background-color:transparent;");
        return tab("المعاملات", sp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. SUPPLIERS PAGE — CRUD with context menu
    // ═══════════════════════════════════════════════════════════════
    @SuppressWarnings("unchecked")
    private Tab suppliersPage() {
        // ── Page title ──
        Label pageTitleLabel = new Label("إدارة الموردين");
        pageTitleLabel.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        // ── Input form ──
        Label formTitle = new Label("إضافة مورد جديد");
        formTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        TextField nameField = input("اسم المورد *"); nameField.setPrefWidth(200);
        TextField phoneField = input("رقم الهاتف *"); phoneField.setPrefWidth(180);
        phoneField.textProperty().addListener((obs, old, newVal) -> {
            if (!newVal.matches("\\d*")) phoneField.setText(old);
        });

        // ── Specialty VBox with CheckBoxes (multi-select from Categories, refreshes live) ──
        VBox specialtyBox = new VBox(6);
        specialtyBox.setStyle("-fx-padding:8 10;-fx-background-color:#f7f9fc;-fx-background-radius:6;");
        Label specialtyTitle = new Label("التخصصات (اختر واحداً أو أكثر):");
        specialtyTitle.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:#a73c3c;");
        final String cbStyle = "-fx-font-size:12px;-fx-text-fill:#333;-fx-padding:2 0;";
        List<CheckBox> specialtyCheckBoxes = new ArrayList<>();
        Runnable refreshSpecialties = () -> {
            // Clear all children (including the title) and rebuild fresh to avoid stale references
            specialtyBox.getChildren().clear();
            specialtyBox.getChildren().add(specialtyTitle);
            specialtyCheckBoxes.clear();
            List<Category> cats = categoryService.getAllCategories();
            boolean hasAny = false;
            for (Category cat : cats) {
                String catName = cat.getName();
                if (catName == null || catName.trim().isEmpty()) continue;
                hasAny = true;
                CheckBox cb = new CheckBox(catName);
                cb.setStyle(cbStyle);
                specialtyCheckBoxes.add(cb);
                specialtyBox.getChildren().add(cb);
            }
            if (!hasAny) {
                Label emptyLbl = new Label("لا توجد تخصصات مسجلة.");
                emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:12px;");
                specialtyBox.getChildren().add(emptyLbl);
            }
        };
        refreshSpecialties.run();
        refreshCallbacks.add(refreshSpecialties);

        TextField addressField = input("العنوان (اختياري)"); addressField.setPrefWidth(200);

        // ── Supplier table ──
        TableView<InventoryService.Supplier> supplierTable = new TableView<>();
        String[] sHeaders = {"تاريخ الإضافة", "الكود", "اسم المورد", "رقم الهاتف", "التخصص", "العنوان"};
        for (int i = 0; i < sHeaders.length; i++) {
            int x = i;
            TableColumn<InventoryService.Supplier, String> col = new TableColumn<>(sHeaders[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> {
                    String dt = v.getValue().createdAt();
                    if (dt == null || dt.isEmpty()) yield "";
                    // عرض التاريخ فقط (بدون وقت)
                    if (dt.contains(" ")) yield dt.split(" ")[0];
                    if (dt.contains("T")) yield dt.split("T")[0];
                    yield dt;
                }
                case 1 -> v.getValue().code();
                case 2 -> v.getValue().name();
                case 3 -> v.getValue().phone();
                case 4 -> v.getValue().specialty() != null ? v.getValue().specialty() : "";
                default -> v.getValue().address() != null ? v.getValue().address() : "";
            }));
            col.setPrefWidth(i == 0 ? 100 : (i == 2 ? 160 : (i == 1 ? 80 : 140)));
            supplierTable.getColumns().add(col);
        }
        supplierTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(supplierTable, "suppliers", 100, 80, 160, 140, 140, 140);
        supplierTable.setPlaceholder(new Label("لا يوجد موردين مسجلين."));



        // ── Add button ──
        Button addBtn = new Button("إضافة المورد");
        addBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        addBtn.setOnAction(e -> run(() -> {
            String specialty = specialtyCheckBoxes.stream()
                .filter(cb -> cb.isSelected())
                .map(cb -> cb.getText())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            if (specialty.isEmpty()) {
                throw new IllegalArgumentException("يرجى اختيار تخصص واحد على الأقل للمورد.");
            }
            service.addSupplier(nameField.getText().trim(), phoneField.getText().trim(), specialty, addressField.getText().trim());
            nameField.clear(); phoneField.clear(); addressField.clear();
            specialtyCheckBoxes.forEach(cb -> cb.setSelected(false));
            loadSuppliers(supplierTable);
            reload();
            info("✅ تمت إضافة المورد بنجاح");
        }));

        // ── Form layout ──
        HBox formRow1 = new HBox(8, phoneField, nameField);
        HBox formRow2 = new HBox(8, addBtn, addressField);
        HBox formRow3 = new HBox(8, specialtyBox);
        formRow1.setAlignment(Pos.CENTER_LEFT); formRow2.setAlignment(Pos.CENTER_LEFT); formRow3.setAlignment(Pos.CENTER_LEFT);
        VBox formBox = new VBox(8, formTitle, formRow1, formRow2, formRow3);
        formBox.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:15;" +
            "-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");

        // ── Context menu ──
        supplierTable.setRowFactory(tv -> {
            TableRow<InventoryService.Supplier> row = new TableRow<>();
            ContextMenu cm = new ContextMenu();
            MenuItem editItem = new MenuItem("تعديل البيانات");
            editItem.setOnAction(ev -> showEditSupplierDialog(supplierTable, row.getItem()));
            MenuItem copyItem = new MenuItem("نسخ الكود");
            copyItem.setOnAction(ev -> {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(row.getItem().code());
                Clipboard.getSystemClipboard().setContent(cc);
                info("تم نسخ الكود: " + row.getItem().code());
            });
            MenuItem deleteItem = new MenuItem("حذف المورد");
            deleteItem.setOnAction(ev -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "هل تريد حذف المورد: " + row.getItem().name() + "؟", ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
                    service.deleteSupplier(row.getItem().id());
            loadSuppliers(supplierTable); reload();
            info("تم حذف المورد");
                }));
            });
            cm.getItems().addAll(editItem, copyItem, new SeparatorMenuItem(), deleteItem);
            row.setContextMenu(cm);
            return row;
        });

        // ── Supplier details card (shows below the table when a supplier is selected) ──
        // ── No card on selection (removed as requested) ──

        // ── Import from customers-style btn ──
        Button exportPdfBtn = new Button("تصدير تقرير الموردين PDF");
        exportPdfBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        exportPdfBtn.setOnAction(e -> {
            FileChooser fc = pdfChooser("تقرير_الموردين.pdf");
            File file = fc.showSaveDialog(stage);
            if (file != null) run(() -> { reports.exportSuppliersPdf(file.toPath()); info("تم تصدير تقرير الموردين بنجاح"); });
        });
        HBox btnRow = new HBox(10, exportPdfBtn);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        // ── Load and layout ──
        VBox page = new VBox(15, pageTitleLabel, formBox, new Separator(),
            new Label("قائمة الموردين"), supplierTable,
            btnRow);
        page.setPadding(new Insets(18));
        VBox.setVgrow(supplierTable, Priority.ALWAYS);
        loadSuppliers(supplierTable);
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:transparent;-fx-background-color:transparent;");
        return tab("الموردين", sp);
    }

    private void loadSuppliers(TableView<InventoryService.Supplier> table) {
        List<InventoryService.Supplier> all = service.allSuppliers();
        table.setItems(FXCollections.observableArrayList(all));
    }

    private void showEditSupplierDialog(TableView<InventoryService.Supplier> table, InventoryService.Supplier s) {
        Stage dialog = new Stage();
        dialog.setTitle("تعديل بيانات المورد");
        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color:#f7f9fc;");
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Label titleLbl = new Label("تعديل بيانات المورد");
        titleLbl.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        Label codeLbl = new Label("الكود: " + s.code());

        TextField nameField = new TextField(s.name());
        nameField.setPromptText("اسم المورد");
        TextField phoneField = new TextField(s.phone());
        phoneField.setPromptText("رقم الهاتف");
        phoneField.textProperty().addListener((obs, old, newVal) -> {
            if (!newVal.matches("\\d*")) phoneField.setText(old);
        });

        // ── Specialty CheckBoxes (multi-select, pre-selected from saved data) ──
        VBox editSpecialtyBox = new VBox(6);
        editSpecialtyBox.setStyle("-fx-padding:8 10;-fx-background-color:#f7f9fc;-fx-background-radius:6;");
        Label editSpecTitle = new Label("التخصصات:");
        editSpecTitle.setStyle("-fx-font-weight:bold;-fx-font-size:12px;-fx-text-fill:#333;");
        editSpecialtyBox.getChildren().add(editSpecTitle);
        String editCbStyle = "-fx-font-size:12px;-fx-text-fill:#333;-fx-padding:2 0;";
        // Split saved specialties (comma-separated) for pre-checking
        String[] savedSpecs = (s.specialty() != null && !s.specialty().isEmpty()) ? s.specialty().split(",") : new String[0];
        List<CheckBox> editSpecCheckBoxes = new ArrayList<>();
        List<Category> cats = categoryService.getAllCategories();
        boolean hasAny = false;
        for (Category cat : cats) {
            String catName = cat.getName();
            if (catName == null || catName.trim().isEmpty()) continue;
            hasAny = true;
            CheckBox cb = new CheckBox(catName);
            cb.setStyle(editCbStyle);
            // Check if this category is in the saved specialties
            for (String saved : savedSpecs) {
                if (saved.trim().equalsIgnoreCase(catName)) {
                    cb.setSelected(true);
                    break;
                }
            }
            editSpecCheckBoxes.add(cb);
            editSpecialtyBox.getChildren().add(cb);
        }
        if (!hasAny) {
            Label emptyLbl = new Label("لا توجد تخصصات مسجلة.");
            emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:12px;");
            editSpecialtyBox.getChildren().add(emptyLbl);
        }

        TextField addressField = new TextField(s.address());
        addressField.setPromptText("العنوان");

        Button saveBtn = new Button("حفظ التعديلات");
        saveBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        saveBtn.setOnAction(ev -> run(() -> {
            String spec = editSpecCheckBoxes.stream()
                .filter(cb -> cb.isSelected())
                .map(cb -> cb.getText())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            service.updateSupplier(s.id(), nameField.getText().trim(), phoneField.getText().trim(),
                spec, addressField.getText().trim());
            loadSuppliers(table);
            dialog.close();
            reload();
            info("✅ تم تحديث بيانات المورد بنجاح");
        }));

        root.getChildren().addAll(titleLbl, codeLbl,
            new Label("اسم المورد:"), nameField,
            new Label("رقم الهاتف:"), phoneField,
            new Label("التخصصات:"), editSpecialtyBox,
            new Label("العنوان:"), addressField,
            saveBtn);
        dialog.setScene(new Scene(root, 380, 500));
        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════
    // 8. CUSTOMERS PAGE (NEW) — CRUD with context menu
    // ═══════════════════════════════════════════════════════════════
    private Tab customersPage() {
        // ── Input form (RTL fields, TextField for type, ComboBox for categories) ──
        Label formTitle = new Label("إضافة عميل جديد");
        formTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        TextField nameField = input("اسم العميل *"); nameField.setPrefWidth(200);
        TextField typeField = input("نوع العميل"); typeField.setPrefWidth(150);
        TextField phoneField = input("رقم التليفون *"); phoneField.setPrefWidth(180);
        phoneField.textProperty().addListener((obs, old, newVal) -> {
            if (!newVal.matches("\\d*")) phoneField.setText(old);
        });
        TextField addressField = input("العنوان (اختياري)"); addressField.setPrefWidth(200);

        // ── Materials multi-select checkboxes from actual categories (refreshable) ──
        customerMaterialsBox = new VBox(4);
        customerMaterialsBox.setStyle("-fx-padding:8;-fx-background-color:#f7f9fc;-fx-background-radius:6; -fx-max-width:300;");
        refreshCustomerMaterials();

        Button addBtn = new Button("إضافة العميل");
        addBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        addBtn.setOnAction(e -> run(() -> {
            String type = typeField.getText().trim();
            String selectedMats = customerMaterialCheckBoxes.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            if (selectedMats.isEmpty()) throw new IllegalArgumentException("يرجى اختيار صنف واحد على الأقل للعميل.");
            service.addCustomer(nameField.getText(), type, phoneField.getText(), addressField.getText(), selectedMats);
            nameField.clear(); typeField.clear(); phoneField.clear(); addressField.clear();
            customerMaterialCheckBoxes.forEach(cb -> cb.setSelected(false));
            loadCustomers(); reload();
            info("تمت إضافة العميل بنجاح");
        }));

        // Fields ordered RTL: name, type, phone in first row, then materials, address, addBtn in second row
        HBox formRow1 = new HBox(8, phoneField, typeField, nameField);
        HBox formRow2 = new HBox(8, addBtn, addressField, customerMaterialsBox);
        formRow1.setAlignment(Pos.CENTER_LEFT); formRow2.setAlignment(Pos.CENTER_LEFT);
        VBox formBox = new VBox(8, formTitle, formRow1, formRow2);
        formBox.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:15;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");

        // ── Customer table ──
        String[] cHeaders = {"تاريخ الإضافة", "الكود", "الاسم", "النوع", "الهاتف", "العنوان", "المواد"};
        for (int i = 0; i < cHeaders.length; i++) {
            int x = i;
            TableColumn<Customer, String> col = new TableColumn<>(cHeaders[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> {
                    String dt = v.getValue().createdAt();
                    if (dt == null || dt.isEmpty()) yield "";
                    // عرض التاريخ فقط (بدون وقت)
                    if (dt.contains(" ")) yield dt.split(" ")[0];
                    if (dt.contains("T")) yield dt.split("T")[0];
                    yield dt;
                }
                case 1 -> v.getValue().code();
                case 2 -> v.getValue().name();
                case 3 -> v.getValue().customerType() != null ? v.getValue().customerType() : "";
                case 4 -> v.getValue().phone();
                case 5 -> v.getValue().address() != null ? v.getValue().address() : "";
                default -> v.getValue().materials();
            }));
            col.setPrefWidth(i == 0 ? 100 : (i == 2 ? 150 : (i == 6 ? 200 : (i == 1 ? 70 : (i == 5 ? 150 : 110)))));
            customerTable.getColumns().add(col);
        }
        customerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(customerTable, "customers", 100, 70, 150, 110, 110, 150, 200);

        // ── Context menu ──
        customerTable.setRowFactory(tv -> {
            TableRow<Customer> row = new TableRow<>();
            ContextMenu cm = new ContextMenu();
            MenuItem editItem = new MenuItem(" تعديل البيانات");
            editItem.setOnAction(ev -> showEditCustomerDialog(row.getItem()));
            MenuItem copyItem = new MenuItem(" نسخ الكود");
            copyItem.setOnAction(ev -> {
                ClipboardContent cc = new ClipboardContent(); cc.putString(row.getItem().code());
                Clipboard.getSystemClipboard().setContent(cc);
                info("تم نسخ الكود: " + row.getItem().code());
            });
            MenuItem deleteItem = new MenuItem(" حذف العميل");
            deleteItem.setOnAction(ev -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "هل تريد حذف العميل: " + row.getItem().name() + "؟", ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> { service.deleteCustomer(row.getItem().id()); loadCustomers(); reload(); info("تم حذف العميل"); }));
            });
            cm.getItems().addAll(editItem, copyItem, new SeparatorMenuItem(), deleteItem);
            row.setContextMenu(cm);
            return row;
        });

        customerTable.setPlaceholder(new Label("لا يوجد عملاء مسجلين."));

        VBox page = new VBox(15, formBox, new Separator(),
            new Label("قائمة العملاء"), customerTable);
        page.setPadding(new Insets(18));
        VBox.setVgrow(customerTable, Priority.ALWAYS);
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:transparent;-fx-background-color:transparent;");
        return tab("العملاء", sp);
    }

    private void showEditCustomerDialog(Customer c) {
        Stage dialog = new Stage(); dialog.setTitle("تعديل بيانات العميل");
        VBox root = new VBox(10); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color:#f7f9fc;");
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        Label title = new Label("تعديل: " + c.name() + " (" + c.code() + ")");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;");
        TextField nameF = new TextField(c.name()); nameF.setPromptText("الاسم"); nameF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        TextField typeF = new TextField(c.customerType() != null ? c.customerType() : ""); typeF.setPromptText("نوع العميل"); typeF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        TextField phoneF = new TextField(c.phone()); phoneF.setPromptText("الهاتف"); phoneF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        phoneF.textProperty().addListener((obs, old, newVal) -> {
            if (!newVal.matches("\\d*")) phoneF.setText(old);
        });
        TextField addrF = new TextField(c.address() != null ? c.address() : ""); addrF.setPromptText("العنوان"); addrF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        List<String> catNames = new ArrayList<>();
        for (Category cat : categoryService.getAllCategories()) {
            catNames.add(cat.getName());
        }
        VBox matEditBox = new VBox(4);
        matEditBox.setStyle("-fx-padding:8;-fx-background-color:white;-fx-background-radius:6;");
        Label matTitle = new Label("المواد:");
        matTitle.setStyle("-fx-font-weight:bold;");
        List<CheckBox> matEditCBs = new ArrayList<>();
        for (String catName : catNames) {
            CheckBox cb = new CheckBox(catName);
            if (c.materials() != null && c.materials().contains(catName)) cb.setSelected(true);
            matEditCBs.add(cb);
        }
        matEditBox.getChildren().add(matTitle);
        matEditBox.getChildren().addAll(matEditCBs);
        Button saveBtn = new Button("حفظ"); saveBtn.setStyle("-fx-background-color:#1b7895;-fx-text-fill:white;");
        saveBtn.setOnAction(ev -> run(() -> {
            String selectedMats = matEditCBs.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            service.updateCustomer(c.id(), nameF.getText(), typeF.getText().trim(), phoneF.getText(), addrF.getText(), selectedMats);                loadCustomers(); reload(); dialog.close(); info("تم التحديث بنجاح");
        }));
        root.getChildren().addAll(title, new Label("الاسم:"), nameF, new Label("النوع:"), typeF,
            new Label("الهاتف:"), phoneF, new Label("العنوان:"), addrF, matEditBox, saveBtn);
        dialog.setScene(new Scene(root, 400, 500)); dialog.show();
    }

    private void loadCustomers() {
        customerTable.setItems(FXCollections.observableArrayList(service.allCustomers()));
        applyDynamicColumns(customerTable, 0, 1, 2, 4); // always show date, code, name, phone; hide type/address/materials if empty
    }

    // ═══════════════════════════════════════════════════════════════
    // 9. REPORTS PAGE — Split daily/monthly/yearly + export buttons
    // ═══════════════════════════════════════════════════════════════
    private Tab reportsPage() {
        VBox content = new VBox(15); content.setPadding(new Insets(20));

        // ── Export buttons for today's data (single row, right-aligned) ──
        String btnStyle = "-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:13px;-fx-font-weight:bold;-fx-padding:9 18;-fx-cursor:hand;";

        Button btnDaily = new Button("التقرير اليومي"); btnDaily.setStyle(btnStyle);
        btnDaily.setOnAction(e -> exportDailyPdf());
        Button btnSales = new Button("المبيعات"); btnSales.setStyle(btnStyle);
        btnSales.setOnAction(e -> { FileChooser fc = pdfChooser("مبيعات.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportSalesPdf(file.toPath()); info("تم التصدير بنجاح"); }); });
        Button btnReturns = new Button("المرتجعات"); btnReturns.setStyle(btnStyle);
        btnReturns.setOnAction(e -> { FileChooser fc = pdfChooser("مرتجعات.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportReturnsPdf(file.toPath()); info("تم التصدير بنجاح"); }); });
        Button btnProducts = new Button("المنتجات"); btnProducts.setStyle(btnStyle);
        btnProducts.setOnAction(e -> { FileChooser fc = pdfChooser("منتجات.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportProductsPdf(file.toPath()); info("تم التصدير بنجاح"); }); });
        Button btnTx = new Button("المعاملات"); btnTx.setStyle(btnStyle);
        btnTx.setOnAction(e -> { FileChooser fc = pdfChooser("معاملات.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportTransactionsPdf(file.toPath()); info("تم التصدير بنجاح"); }); });
        Button btnCash = new Button("الخزينة"); btnCash.setStyle(btnStyle);
        btnCash.setOnAction(e -> { FileChooser fc = pdfChooser("خزينة.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportCashOpsPdf(file.toPath()); info("تم التصدير بنجاح"); }); });
        Button btnCustomers = new Button("العملاء"); btnCustomers.setStyle(btnStyle);
        btnCustomers.setOnAction(e -> { FileChooser fc = pdfChooser("عملاء.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportAllCustomersPdf(file.toPath(), service.allCustomers()); info("تم التصدير بنجاح"); }); });
        Button btnSuppliersReport = new Button(" الموردين"); btnSuppliersReport.setStyle(btnStyle);
        btnSuppliersReport.setOnAction(e -> { FileChooser fc = pdfChooser("تقرير_الموردين.pdf"); File file = fc.showSaveDialog(stage); if (file != null) run(() -> { reports.exportSuppliersDetailedPdf(file.toPath()); info("تم التصدير بنجاح"); }); });

        HBox exportBox = new HBox(10, btnDaily, btnSales, btnReturns, btnProducts, btnTx, btnCash, btnCustomers, btnSuppliersReport);
        exportBox.setAlignment(Pos.CENTER_LEFT);
        exportBox.setStyle("-fx-background-color:white;-fx-background-radius:10;-fx-padding:14 18;-fx-effect:dropshadow(gaussian,#00000012,6,0,0,2);");

        // ── Page title ──
        Label pageTitle = new Label("التقارير");
        pageTitle.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        // ── Archived Daily Reports Section (paginated: 30 at a time) ──
        Label dailyArchiveTitle = new Label("التقارير اليومية");
        dailyArchiveTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        VBox dailyArchiveReportsBox = new VBox(10);
        final int[] dailyOffset = {0};
        final int DAILY_PAGE_SIZE = 30;
        final int totalDays = service.countDailyReportArchives();
        Button loadMoreDailyBtn = new Button("عرض المزيد من الأيام");
        loadMoreDailyBtn.setStyle("-fx-background-color:#f0f4f9;-fx-background-radius:6;-fx-text-fill:#1b7895;-fx-font-size:12px;-fx-padding:6 20;-fx-font-weight:bold;-fx-border-color:#c5d0dc;-fx-border-radius:6;");
        java.util.function.Consumer<Button> loadDailyBatch = new java.util.function.Consumer<>() {
            @Override public void accept(Button btn) {
                java.util.List<InventoryService.DailyReportArchive> batch = service.allDailyReportArchives(DAILY_PAGE_SIZE, dailyOffset[0]);
                if (batch.isEmpty()) {
                    if (dailyOffset[0] == 0) {
                        dailyArchiveReportsBox.getChildren().add(new Label("لا توجد تقارير يومية مؤرشفة."));
                    }
                    btn.setVisible(false);
                    btn.setManaged(false);
                    return;
                }
                dailyOffset[0] += batch.size();
                for (InventoryService.DailyReportArchive arch : batch) {
                    VBox cardBox = new VBox(6);
                    cardBox.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:12;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");
                    cardBox.setMaxWidth(400);

                    // Format date with day name
                    String displayDate = arch.reportDate();
                    try {
                        java.time.LocalDate ad = java.time.LocalDate.parse(displayDate);
                        String[] arabicDays = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
                        String[] arabicMonths = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
                        String dayName = arabicDays[ad.getDayOfWeek().getValue() % 7];
                        displayDate = dayName + " " + ad.getDayOfMonth() + " " + arabicMonths[ad.getMonthValue() - 1] + " " + ad.getYear();
                    } catch (Exception ignored) {}

                    Label dateLabel = new Label(displayDate);
                    dateLabel.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

                    // Use structured archive fields
                    VBox infoRow = new VBox(5);
                    String statLabelStyle = "-fx-font-size:11px;-fx-text-fill:#555;-fx-padding:0 8;";

                    HBox countsRow = new HBox(12);
                    countsRow.setAlignment(Pos.CENTER_RIGHT);
                    countsRow.getChildren().addAll(
                        new Label("بيع: " + arch.saleCount()) {{ setStyle(statLabelStyle); }},
                        new Label("شراء: " + arch.purchaseCount()) {{ setStyle(statLabelStyle); }},
                        new Label("مرتجع: " + arch.returnCount()) {{ setStyle(statLabelStyle); }}
                    );

                    HBox financeRow = new HBox(12);
                    financeRow.setAlignment(Pos.CENTER_RIGHT);
                    financeRow.getChildren().addAll(
                        new Label("المبيعات: " + money(arch.totalSales())) {{ setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#27a068;"); }},
                        new Label("المشتريات: " + money(arch.totalPurchases())) {{ setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#a73c3c;"); }},
                        new Label("الربح: " + money(arch.totalProfit())) {{ setStyle("-fx-font-size:11px;-fx-font-weight:bold;-fx-text-fill:#c07030;"); }}
                    );

                    infoRow.getChildren().addAll(countsRow, financeRow);

                    HBox btnRow = new HBox(8);
                    btnRow.setAlignment(Pos.CENTER_LEFT);

                    Button viewBtn = new Button("عرض");
                    viewBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:5 14;-fx-font-weight:bold;");
                    String archiveDate = arch.reportDate();
                    String finalDisplayDate = displayDate;
                    viewBtn.setOnAction(e -> showArchivedReport(archiveDate, finalDisplayDate));

                    Button pdfBtn = new Button("تصدير PDF");
                    pdfBtn.setStyle("-fx-background-color:#7050a0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:5 14;-fx-font-weight:bold;");
                    pdfBtn.setOnAction(e -> {
                        FileChooser fc = pdfChooser("تقرير_" + arch.reportDate() + ".pdf");
                        File file = fc.showSaveDialog(stage);
                        if (file != null) run(() -> {
                            reports.exportDailyPdf(file.toPath(), java.time.LocalDate.parse(arch.reportDate()));
                            info("تم تصدير التقرير بنجاح");
                        });
                    });

                    btnRow.getChildren().addAll(viewBtn, pdfBtn);
                    cardBox.getChildren().addAll(dateLabel, infoRow, btnRow);
                    dailyArchiveReportsBox.getChildren().add(cardBox);
                }
                if (dailyOffset[0] >= totalDays) {
                    btn.setVisible(false);
                    btn.setManaged(false);
                }
            }
        };
        loadDailyBatch.accept(loadMoreDailyBtn);
        loadMoreDailyBtn.setOnAction(e -> loadDailyBatch.accept(loadMoreDailyBtn));
        dailyArchiveReportsBox.getChildren().add(loadMoreDailyBtn);
        // When refreshDailyArchives is triggered (e.g. after a day closes), reset and reload the first page
        refreshDailyArchives = () -> {
            Platform.runLater(() -> {
                dailyOffset[0] = 0;
                dailyArchiveReportsBox.getChildren().clear();
                loadDailyBatch.accept(loadMoreDailyBtn);
                dailyArchiveReportsBox.getChildren().add(loadMoreDailyBtn);
            });
        };

        // ── Monthly Reports Section ──
        Label monthlyTitle = new Label("التقارير الشهرية");
        monthlyTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        VBox monthlyBox = new VBox(10);
        refreshMonthlyArchives = () -> Platform.runLater(() -> {
            monthlyBox.getChildren().clear();
            List<MonthlyReport> mrs = service.allMonthlyReports();
            if (mrs.isEmpty()) { monthlyBox.getChildren().add(new Label("لا توجد تقارير شهرية بعد.")); }
            else {
                for (MonthlyReport mr : mrs) {
                VBox card = new VBox(6);
                card.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:12;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");
                String monthDisplay = mr.yearMonth();
                try {
                    java.time.YearMonth ymParsed = java.time.YearMonth.parse(mr.yearMonth());
                    String[] arabicMonths = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
                    monthDisplay = arabicMonths[ymParsed.getMonthValue() - 1] + " " + ymParsed.getYear();
                } catch (Exception ignored) {}
                Label monthLbl = new Label(monthDisplay); monthLbl.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
                HBox statsRow = new HBox(15); statsRow.setAlignment(Pos.CENTER_RIGHT);
                statsRow.getChildren().addAll(createStatBox("المبيعات", money(mr.totalSales()), "#1b7895"), createStatBox("الربح", money(mr.totalProfit()), "#27a068"),
                    createStatBox("الجملة", money(mr.totalWholesale()), "#c07030"), createStatBox("الأيام", String.valueOf(mr.daysCount()), "#7050a0"));
                Label balanceLbl = new Label("الرصيد الختامي: " + money(mr.closingBalance()));
                balanceLbl.setStyle("-fx-font-size:12px;-fx-text-fill:" + (mr.closingBalance() >= 0 ? "#27a068" : "#a73c3c") + ";");
                Button viewBtn = new Button(" عرض التفاصيل"); viewBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:5;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:4 10;");
                MonthlyReport mrRef = mr;
                viewBtn.setOnAction(e -> showMonthlyReportDetail(mrRef));
                Button monthlyPdfBtn = new Button(" تصدير PDF"); monthlyPdfBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:5;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:4 10;");
                monthlyPdfBtn.setOnAction(ev -> {
                    String[] parts = mrRef.yearMonth().split("-");
                    if (parts.length < 2) { warn("بيانات الشهر غير صالحة للتصدير."); return; }
                    int yr2 = Integer.parseInt(parts[0]);
                    int mn = Integer.parseInt(parts[1]);
                    FileChooser fc = new FileChooser();
                    fc.setInitialFileName("تقرير_شهري_" + mrRef.yearMonth() + ".pdf");
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
                    File f = fc.showSaveDialog(stage);
                    if (f != null) { try { reports.exportMonthlyPdf(f.toPath(), yr2, mn); info("تم تصدير التقرير الشهري بنجاح."); } catch (Exception ex) { error(ex.getMessage()); } }
                });
                HBox btnRow = new HBox(8); btnRow.setAlignment(Pos.CENTER_RIGHT); btnRow.getChildren().addAll(viewBtn, monthlyPdfBtn);
                card.getChildren().addAll(monthLbl, statsRow, balanceLbl, btnRow);                    monthlyBox.getChildren().add(card);
                }
            }
        });

            // ── Yearly Reports Section ──
            Label yearlyTitle = new Label("التقارير السنوية");
        yearlyTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        VBox yearlyBox = new VBox(10);
        List<YearlyReport> yearlyReports = service.allYearlyReports();
        if (yearlyReports.isEmpty()) { yearlyBox.getChildren().add(new Label("لا توجد تقارير سنوية بعد.")); }
        else {
            for (YearlyReport yr : yearlyReports) {
                VBox card = new VBox(6);
                card.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:12;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);");
                Label yearLbl = new Label("سنة " + yr.year()); yearLbl.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
                HBox statsRow = new HBox(15); statsRow.setAlignment(Pos.CENTER_RIGHT);
                statsRow.getChildren().addAll(createStatBox("المبيعات", money(yr.totalSales()), "#1b7895"), createStatBox("الربح", money(yr.totalProfit()), "#27a068"),
                    createStatBox("الجملة", money(yr.totalWholesale()), "#c07030"), createStatBox("الأشهر", String.valueOf(yr.monthsCount()), "#7050a0"));
                Label balanceLbl = new Label("الرصيد الختامي: " + money(yr.closingBalance()));
                balanceLbl.setStyle("-fx-font-size:12px;-fx-text-fill:" + (yr.closingBalance() >= 0 ? "#27a068" : "#a73c3c") + ";");
                Button viewBtn = new Button(" عرض التفاصيل"); viewBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:5;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:4 10;");
                YearlyReport yrRef = yr;
                viewBtn.setOnAction(e -> showYearlyReportDetail(yrRef));
                Button pdfBtn = new Button(" تصدير PDF"); pdfBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:5;-fx-text-fill:white;-fx-font-size:11px;-fx-padding:4 10;");
                pdfBtn.setOnAction(ev -> {
                    FileChooser fc = new FileChooser();
                    fc.setInitialFileName("تقرير_سنوي_" + yrRef.year() + ".pdf");
                    fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
                    File f = fc.showSaveDialog(stage);
                    if (f != null) { try { reports.exportYearlyPdf(f.toPath(), yrRef.year()); info("تم تصدير التقرير السنوي بنجاح."); } catch (Exception ex) { error(ex.getMessage()); } }
                });
                HBox btnRow = new HBox(8); btnRow.setAlignment(Pos.CENTER_RIGHT); btnRow.getChildren().addAll(viewBtn, pdfBtn);
                card.getChildren().addAll(yearLbl, statsRow, balanceLbl, btnRow);
                yearlyBox.getChildren().add(card);
            }
        }

        // ── Customer Reports Section (refreshed from reload()) ──
        Label customerReportsTitle = new Label("تقارير العملاء");
        customerReportsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        customerReportsBox = new VBox(10);
        refreshCustomerReports();

        // ── Supplier Reports Section ──
        Label supplierReportsTitle = new Label("تقارير الموردين");
        supplierReportsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        supplierReportsBox = new VBox(10);
        refreshSupplierReports();

        // ── Tools Report Section ──
        Label toolsReportTitle = new Label("تقرير العدد");
        toolsReportTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;margin-top:15;");
        VBox toolsReportCard = new VBox(10);
        toolsReportCard.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:14;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);-fx-border-color:#d0d5dd;-fx-border-radius:8;");

        List<ToolRow> allTools = service.allTools();
        List<InventoryService.RentalRecord> allRentals = service.getAllRentals();
        int toolsCount = allTools.size();
        int rentalsCount = allRentals.size();
        double totalRentalIncome = allRentals.stream().mapToDouble(InventoryService.RentalRecord::price).sum();

        HBox toolsStatsRow = new HBox(15);
        toolsStatsRow.setAlignment(Pos.CENTER_LEFT);
        toolsStatsRow.getChildren().addAll(
            createStatBox("عدد العدد", String.valueOf(toolsCount), "#1b7895"),
            createStatBox("عمليات التأجير", String.valueOf(rentalsCount), "#7050a0"),
            createStatBox("إجمالي الإيراد", money(totalRentalIncome), "#27a068")
        );



        Button toolsDetailsBtn = new Button("عرض التفاصيل");
        toolsDetailsBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");
        toolsDetailsBtn.setOnAction(e -> showToolsReportDetail());

        Button toolsPdfBtn = new Button("تصدير PDF");
        toolsPdfBtn.setStyle("-fx-background-color:#7050a0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");
        toolsPdfBtn.setOnAction(e -> {
            FileChooser fc = pdfChooser("تقرير_العدد_والتأجير.pdf");
            File file = fc.showSaveDialog(stage);
            if (file != null) run(() -> { reports.exportToolsPdf(file.toPath()); info("تم تصدير تقرير العدد بنجاح"); });
        });

        HBox toolsBtnRow = new HBox(10, toolsDetailsBtn, toolsPdfBtn);
        toolsBtnRow.setAlignment(Pos.CENTER_LEFT);
        toolsReportCard.getChildren().addAll(toolsStatsRow, toolsBtnRow);        content.getChildren().addAll(pageTitle, exportBox, dailyArchiveTitle, dailyArchiveReportsBox, monthlyTitle, monthlyBox, yearlyTitle, yearlyBox, customerReportsTitle, customerReportsBox, supplierReportsTitle, supplierReportsBox, toolsReportTitle, toolsReportCard);
        ScrollPane sp = new ScrollPane(content); sp.setFitToWidth(true);
        return tab("التقارير", sp);
    }

    // ═══════════════════════════════════════════════════════════════
    // 11. TOOLS PAGE — Add and manage tools/equipment
    // ═══════════════════════════════════════════════════════════════
    private final VBox toolsCardsBox = new VBox(12);

    private Tab toolsPage() {
        Label pageTitle = new Label("إدارة العدد — إضافة عدة جديدة");
        pageTitle.setStyle("-fx-font-size:18px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        String fieldStyle = "-fx-font-size:13px;";
        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;" +
            "-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);";

        // ── Form fields ──
        TextField nameField = input("اسم العدة *");
        nameField.setPrefWidth(200);
        nameField.setStyle(fieldStyle);

        TextField sizeField = input("الحجم");
        sizeField.setPrefWidth(150);
        sizeField.setStyle(fieldStyle);

        TextField priceField = numericInput("سعر التأجير *");
        priceField.setPrefWidth(150);
        priceField.setStyle(fieldStyle);

        Button addBtn = new Button("إضافة عدة");
        addBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;" +
            "-fx-font-weight:bold;-fx-font-size:14px;-fx-padding:10 30;");

        Label statusLbl = new Label("");
        statusLbl.setStyle("-fx-text-fill:#27a068;-fx-font-weight:bold;-fx-font-size:12px;");

        addBtn.setOnAction(e -> run(() -> {
            String name = nameField.getText().trim();
            String size = sizeField.getText().trim();
            String priceStr = priceField.getText().trim();

            if (name.isEmpty()) { warn("يرجى إدخال اسم العدة"); return; }
            if (priceStr.isEmpty()) { warn("يرجى إدخال سعر التأجير"); return; }

            double price = NumberUtil.parseNumber(priceStr);
            if (price <= 0) { warn("سعر التأجير يجب أن يكون أكبر من صفر"); return; }

            service.addTool(name, size, "", price, "");
            nameField.clear();
            sizeField.clear();
            priceField.clear();
            reloadTools(); reload();
            statusLbl.setText("✓ تمت إضافة العدة بنجاح");
        }));

        // ── Form card ──
        HBox formRow = new HBox(10, nameField, sizeField, priceField, addBtn);
        formRow.setAlignment(Pos.CENTER_LEFT);
        VBox formCard = new VBox(12, pageTitle, formRow, statusLbl);
        formCard.setStyle(cardStyle);

        // ── Tools cards container ──
        VBox toolsCardsBox = new VBox(12);

        String toolCardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;" +
            "-fx-effect:dropshadow(gaussian,#00000012,6,0,0,2);" +
            "-fx-border-color:#e0e4ea;-fx-border-radius:10;";
        String labelStyle = "-fx-font-size:11px;-fx-text-fill:#888;-fx-font-weight:bold;";
        String valueStyle = "-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#333;";
        String statusReadyStyle = "-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:11px;-fx-padding:3 12;";
        String statusRentedStyle = "-fx-background-color:#a73c3c;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:11px;-fx-padding:3 12;";

        // ── Rental history table (defined before refreshCards so the Runnable can capture it) ──
        TableView<InventoryService.RentalRecord> rentalTable = new TableView<>();

        Runnable refreshCards = () -> {
            toolsCardsBox.getChildren().clear();
            List<ToolRow> tools = service.allTools();
            if (tools.isEmpty()) {
                Label emptyLbl = new Label("لا توجد عدد مسجلة.");
                emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:14px;");
                toolsCardsBox.getChildren().add(emptyLbl);
                return;
            }
            for (ToolRow t : tools) {
                // ── Card header: code + status badge ──
                Label codeLbl = new Label(t.code() != null && !t.code().isEmpty() ? t.code() : "—");
                codeLbl.setStyle("-fx-font-size:13px;-fx-text-fill:#1b7895;-fx-font-weight:bold;");

                Label toolStatusLbl = new Label("جاهزة".equals(t.status()) ? "✓ جاهزة" : "تم تأجيرها");
                toolStatusLbl.setStyle("جاهزة".equals(t.status()) ? statusReadyStyle : statusRentedStyle);

                HBox headerRow = new HBox(10, codeLbl, new Region(), toolStatusLbl);
                HBox.setHgrow(new Region(), Priority.ALWAYS);
                headerRow.setAlignment(Pos.CENTER_LEFT);

                // ── Card body: fields ──
                String dateStr = "—";
                String dt = t.createdAt();
                if (dt != null && !dt.isEmpty()) {
                    try {
                        String str = dt.contains(" ") ? dt.split(" ")[0] : dt;
                        if (str.contains("T")) str = str.split("T")[0];
                        if (str.contains("-")) {
                            String[] parts = str.split("-");
                            dateStr = parts[2] + "/" + parts[1] + "/" + parts[0];
                        } else dateStr = str;
                    } catch (Exception ignored) { dateStr = dt; }
                }

                Label nameVal = new Label(t.name());
                nameVal.setStyle(valueStyle);
                Label sizeVal = new Label(t.size() != null && !t.size().isEmpty() ? t.size() : "—");
                sizeVal.setStyle(valueStyle);
                Label priceVal = new Label(money(t.rentalPrice()));
                priceVal.setStyle(valueStyle);
                Label dateVal = new Label(dateStr);
                dateVal.setStyle(valueStyle);
                String statusText = "جاهزة".equals(t.status()) ? "متاحة" : "مؤجرة";
                String statusColor = "جاهزة".equals(t.status()) ? "#27a068" : "#a73c3c";
                Label statusVal = new Label(statusText);
                statusVal.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:" + statusColor + ";");

                final int toolId = t.id();

                // ── Edit button ──
                Button editBtn = new Button("تعديل");
                editBtn.setStyle("-fx-background-color:#f0ad4e;-fx-background-radius:6;-fx-text-fill:white;" +
                    "-fx-font-weight:bold;-fx-font-size:12px;-fx-padding:6 18;");
                editBtn.setOnAction(ev -> {
                    // Create edit dialog
                    Stage dialog = new Stage(); dialog.setTitle("تعديل العدة: " + t.code());
                    VBox dRoot = new VBox(12); dRoot.setPadding(new Insets(20));
                    TextField nField = input("اسم العدة"); nField.setText(t.name());
                    TextField sField = input("الحجم"); sField.setText(t.size() != null && !t.size().isEmpty() ? t.size() : "");
                    TextField pField = numericInput("سعر التأجير"); pField.setText(String.valueOf(t.rentalPrice()));
                    Button saveBtn = new Button("حفظ");
                    saveBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
                    saveBtn.setOnAction(saveEv -> run(() -> {
                        String n = nField.getText().trim();
                        if (n.isEmpty()) { warn("اسم العدة مطلوب"); return; }
                        String sz = sField.getText().trim();
                        String ps = pField.getText().trim();
                        if (ps.isEmpty()) { warn("سعر التأجير مطلوب"); return; }
                        double pp = NumberUtil.parseNumber(ps);
                        if (pp <= 0) { warn("سعر التأجير يجب أن يكون أكبر من صفر"); return; }
                        service.editTool(toolId, n, sz, pp);
                        refreshToolsCards.run(); reload();
                        dialog.close();
                        info("تم تعديل العدة بنجاح");
                    }));
                    Button cancelBtn = new Button("إلغاء");
                    cancelBtn.setStyle("-fx-background-color:#b0b0b0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
                    cancelBtn.setOnAction(ce -> dialog.close());
                    HBox btnRow = new HBox(10, saveBtn, cancelBtn);
                    btnRow.setAlignment(Pos.CENTER_LEFT);
                    dRoot.getChildren().addAll(new Label("تعديل بيانات العدة"), nField, sField, pField, btnRow);
                    dRoot.setStyle("-fx-background-color:#f7f9fc;");
                    dialog.setScene(new Scene(dRoot, 350, 300)); dialog.show();
                });

                // ── Delete button ──
                Button deleteBtn = new Button("حذف");
                deleteBtn.setStyle("-fx-background-color:#a73c3c;-fx-background-radius:6;-fx-text-fill:white;" +
                    "-fx-font-weight:bold;-fx-font-size:12px;-fx-padding:6 18;");
                deleteBtn.setOnAction(ev -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "هل أنت متأكد من حذف العدة " + t.name() + "؟",
                        ButtonType.YES, ButtonType.NO);
                    confirm.setTitle("تأكيد الحذف");
                    confirm.setHeaderText(null);
                    confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
                        service.deleteTool(toolId);
                        refreshToolsCards.run(); reload();
                        info("تم حذف العدة بنجاح");
                    }));
                });

                // ── Rent button ──
                Button rentBtn = new Button("تأجير");
                String rentBtnStyle = "-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;" +
                    "-fx-font-weight:bold;-fx-font-size:12px;-fx-padding:6 18;";
                String endRentBtnStyle = "-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;" +
                    "-fx-font-weight:bold;-fx-font-size:12px;-fx-padding:6 18;";
                rentBtn.setStyle(rentBtnStyle);

                // ── End rental button (visible only when rented) ──
                Button endRentBtn = new Button("فك التأجير");
                endRentBtn.setStyle(endRentBtnStyle);
                endRentBtn.setVisible(false);
                endRentBtn.setManaged(false);

                if ("تم تأجيرها".equals(t.status())) {
                    rentBtn.setVisible(false);
                    rentBtn.setManaged(false);
                    endRentBtn.setVisible(true);
                    endRentBtn.setManaged(true);
                }

                endRentBtn.setOnAction(ev -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "هل أنت متأكد من إنهاء تأجير هذه العدة؟",
                        ButtonType.YES, ButtonType.NO);
                    confirm.setTitle("تأكيد إنهاء التأجير");
                    confirm.setHeaderText(null);
                    confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
                        service.unrentTool(toolId);
                        refreshToolsCards.run(); reload();
                        info("تم إنهاء تأجير العدة: " + t.name());
                    }));
                });

                rentBtn.setOnAction(ev -> {
                    Stage rentDialog = new Stage();
                    rentDialog.setTitle("تأجير العدة: " + t.code());
                    VBox rRoot = new VBox(12); rRoot.setPadding(new Insets(20));
                    rRoot.setStyle("-fx-background-color:#f7f9fc;");

                    Label titleLbl = new Label("تأجير العدة: " + t.name());
                    titleLbl.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

                    TextField rentPriceField = numericInput("سعر التأجير");
                    rentPriceField.setText(String.valueOf(t.rentalPrice()));

                    TextField renterNameField = input("اسم المستأجر *");

                    Button doneBtn = new Button("تم");
                    doneBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:8 25;");
                    Button cancelBtn = new Button("إلغاء");
                    cancelBtn.setStyle("-fx-background-color:#b0b0b0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:8 25;");
                    cancelBtn.setOnAction(ce -> rentDialog.close());

                    doneBtn.setOnAction(de -> run(() -> {
                        String ps = rentPriceField.getText().trim();
                        String rn = renterNameField.getText().trim();
                        if (ps.isEmpty()) { warn("يرجى إدخال سعر التأجير"); return; }
                        if (rn.isEmpty()) { warn("يرجى إدخال اسم المستأجر"); return; }
                        double pp = NumberUtil.parseNumber(ps);
                        if (pp <= 0) { warn("سعر التأجير يجب أن يكون أكبر من صفر"); return; }
                        service.markToolAsRented(toolId, pp, rn);
                        refreshToolsCards.run(); reload();
                        rentDialog.close();
                        info("تم تأجير العدة: " + t.name());
                    }));

                    HBox btnRow = new HBox(10, doneBtn, cancelBtn);
                    btnRow.setAlignment(Pos.CENTER_LEFT);
                    rRoot.getChildren().addAll(titleLbl, rentPriceField, renterNameField, btnRow);
                    rentDialog.setScene(new Scene(rRoot, 350, 280));
                    rentDialog.show();
                });

                // ── Build info grid ──
                java.util.function.BiFunction<String, Label, VBox> df = (lbl, val) -> {
                    Label tl = new Label(lbl);
                    tl.setStyle(labelStyle);
                    return new VBox(2, tl, val);
                };

                HBox infoRow = new HBox(25,
                    df.apply("اسم العدة", nameVal),
                    df.apply("الحجم", sizeVal),
                    df.apply("سعر التأجير", priceVal),
                    df.apply("تاريخ الإضافة", dateVal),
                    df.apply("الحالة", statusVal)
                );
                infoRow.setAlignment(Pos.CENTER_RIGHT);

                // ── Assemble card ──
                HBox cardBottom = new HBox(10, editBtn, deleteBtn, new Region(), rentBtn, endRentBtn);
                HBox.setHgrow(new Region(), Priority.ALWAYS);
                cardBottom.setAlignment(Pos.CENTER_LEFT);

                VBox card = new VBox(10, headerRow, new Separator(), infoRow, cardBottom);
                card.setStyle(cardStyle);
                toolsCardsBox.getChildren().add(card);
            }

            // ── Refresh rental history table ──
            List<InventoryService.RentalRecord> allRentals = service.getAllRentals();
            rentalTable.setItems(FXCollections.observableArrayList(allRentals));
        };

        // Store as field-level Runnable so reloadTools() can trigger it
        refreshToolsCards = refreshCards;
        refreshCards.run();

        // ── PDF Export button ──
        Button exportPdfBtn = new Button("تصدير PDF");
        exportPdfBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;" +
            "-fx-font-weight:bold;-fx-font-size:13px;-fx-padding:10 25;");
        exportPdfBtn.setOnAction(e -> {
            FileChooser fc = pdfChooser("تقرير_العدد_والتأجير.pdf");
            File file = fc.showSaveDialog(stage);
            if (file != null) run(() -> { reports.exportToolsPdf(file.toPath()); info("تم تصدير تقرير العدد والتأجير بنجاح"); });
        });
        HBox pdfBtnRow = new HBox(exportPdfBtn);
        pdfBtnRow.setAlignment(Pos.CENTER_LEFT);

        // ── Rental history table ──
        Label historyTitle = new Label("سجل عمليات التأجير");
        historyTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1b7895;");

        rentalTable.setStyle("-fx-background-radius:10;");
        String[] rtHeaders = {"التاريخ", "الوقت", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الربح"};
        for (int i = 0; i < rtHeaders.length; i++) {
            int x = i;
            TableColumn<InventoryService.RentalRecord, String> col = new TableColumn<>(rtHeaders[i]);
            col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                case 0 -> v.getValue().date();
                case 1 -> v.getValue().time();
                case 2 -> v.getValue().toolCode() != null && !v.getValue().toolCode().isEmpty() ? v.getValue().toolCode() : "—";
                case 3 -> v.getValue().toolName();
                case 4 -> v.getValue().size() != null && !v.getValue().size().isEmpty() ? v.getValue().size() : "—";
                case 5 -> v.getValue().renterName() != null && !v.getValue().renterName().isEmpty() ? v.getValue().renterName() : "—";
                case 6 -> money(v.getValue().price());
                case 7 -> money(v.getValue().price());  // profit = price for rentals
                default -> "—";
            }));
            col.setPrefWidth(switch (x) {
                case 0 -> 90;
                case 1 -> 70;
                case 2 -> 80;
                case 3 -> 140;
                case 4 -> 70;
                case 5 -> 120;
                case 6 -> 90;
                case 7 -> 80;
                default -> 0;
            });
            rentalTable.getColumns().add(col);
        }
        rentalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        attachColumnPrefs(rentalTable, "rental_history", 90, 70, 80, 140, 70, 120, 90, 80);
        rentalTable.setPlaceholder(new Label("لا توجد عمليات تأجير."));
        rentalTable.setPrefHeight(300);
        VBox.setVgrow(rentalTable, Priority.NEVER);

        VBox page = new VBox(15, formCard, pdfBtnRow, toolsCardsBox, historyTitle, rentalTable);
        page.setPadding(new Insets(20));            VBox.setVgrow(toolsCardsBox, Priority.ALWAYS);

        // Refresh rental table initially
        ScrollPane sp = new ScrollPane(page);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");
        return tab("العدد", sp);
    }

    private void reloadTools() {
        refreshToolsCards.run();
    }

    private void refreshTodayRentals() {
        todayRentalsBox.getChildren().clear();
        List<InventoryService.TodayRental> rentals = service.getTodayRentals();
        if (rentals.isEmpty()) {
            Label emptyLbl = new Label("لا توجد تأجيرات اليوم.");
            emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:12px;");
            todayRentalsBox.getChildren().add(emptyLbl);
            return;
        }
        // Summary: count + total
        int count = rentals.size();
        double total = rentals.stream().mapToDouble(r -> r.price()).sum();
        Label summaryLbl = new Label("عدد التأجيرات: " + count + "  |  الإيراد: " + money(total));
        summaryLbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#1b7895;");
        todayRentalsBox.getChildren().add(summaryLbl);

        // Last 5 rentals
        int limit = Math.min(5, rentals.size());
        for (int i = 0; i < limit; i++) {
            InventoryService.TodayRental r = rentals.get(i);
            String displayName = r.toolName();
            if (!r.toolCode().isEmpty()) displayName += " (" + r.toolCode() + ")";
            Label nameLbl = new Label(displayName);
            nameLbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#333;");
            Label timeLbl = new Label(r.time());
            timeLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
            HBox rentalRow = new HBox(10, nameLbl, timeLbl);
            rentalRow.setAlignment(Pos.CENTER_LEFT);
            todayRentalsBox.getChildren().add(rentalRow);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 12. SETTINGS PAGE — Export buttons removed
    // ═══════════════════════════════════════════════════════════════
    private Tab settingsPage() {
        Label pageTitle = new Label("الإعدادات — إدارة الأصناف");
        pageTitle.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:16 20;-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);";
        String fieldStyle = "-fx-font-size:13px;";

        // ═══════════════════════════════════════════════════════════
        // Category Cards List (declared early so the add-cat lambda can refresh it)
        // ═══════════════════════════════════════════════════════════
        VBox categoryCardsBox = new VBox(10);
        categoryCardsBox.setPadding(new Insets(5));

        // ═══════════════════════════════════════════════════════════
        // SECTION: Add New Category Card
        // ═══════════════════════════════════════════════════════════
        Label addCatTitle = new Label("إضافة صنف جديد");
        addCatTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#1b7895;");

        TextField catCodeField = input("كود الصنف (حروف فقط)");
        catCodeField.setPrefWidth(250);
        TextField catNameField = input("اسم الصنف");
        catNameField.setPrefWidth(250);

        // Units multi-select (checkboxes)
        String[] unitOptions = {"بستلة", "زجاجة", "عبوة", "جالون", "جالون دوبل", "جركن", "قطعة", "مقاس", "طول", "الوزن"};
        VBox unitsBox = new VBox(4);
        unitsBox.setStyle("-fx-padding:8;-fx-background-color:#f7f9fc;-fx-background-radius:6;");
        Label unitsTitle = new Label("الوحدة:");
        unitsTitle.setStyle("-fx-font-weight:bold;-fx-font-size:13px;");
        List<CheckBox> unitCheckBoxes = new ArrayList<>();
        for (String u : unitOptions) {
            CheckBox cb = new CheckBox(u);
            cb.setStyle(fieldStyle);
            unitCheckBoxes.add(cb);
        }
        unitsBox.getChildren().add(unitsTitle);
        unitsBox.getChildren().addAll(unitCheckBoxes);

        // Measurement units multi-select (checkboxes)
        String[] measOptions = {"كيلوجرام", "جرام", "لتر", "ميللي لتر", "متر", "عدد", "متر مسطح"};
        VBox measBox = new VBox(4);
        measBox.setStyle("-fx-padding:8;-fx-background-color:#f7f9fc;-fx-background-radius:6;");
        Label measTitle = new Label("وحدة القياس:");
        measTitle.setStyle("-fx-font-weight:bold;-fx-font-size:13px;");
        List<CheckBox> measCheckBoxes = new ArrayList<>();
        for (String m : measOptions) {
            CheckBox cb = new CheckBox(m);
            cb.setStyle(fieldStyle);
            measCheckBoxes.add(cb);
        }
        measBox.getChildren().add(measTitle);
        measBox.getChildren().addAll(measCheckBoxes);

        // ── Inline editing state ──
        // When editingCategoryId[0] != -1, the form is in "edit" mode instead of "add" mode
        final int[] editingCategoryId = {-1};

        // ── Button must be declared before startEdit so the callback can reference it ──
        Button addCatBtn = new Button("إضافة الصنف");
        addCatBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");

        // ── Inline edit callback — populates the add form with the selected category's data ──
        java.util.function.Consumer<Category> startEdit = (cat) -> {
            catCodeField.setText(cat.getCode() != null ? cat.getCode() : "");
            catNameField.setText(cat.getName());
            for (CheckBox cb : unitCheckBoxes) {
                cb.setSelected(cat.getUnits() != null && !cat.getUnits().isEmpty()
                    && java.util.Arrays.asList(cat.getUnits().split(",")).contains(cb.getText()));
            }
            for (CheckBox cb : measCheckBoxes) {
                cb.setSelected(cat.getMeasurementUnits() != null && !cat.getMeasurementUnits().isEmpty()
                    && java.util.Arrays.asList(cat.getMeasurementUnits().split(",")).contains(cb.getText()));
            }
            editingCategoryId[0] = cat.getId();
            addCatBtn.setText("حفظ التعديلات");
            addCatBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
            addCatTitle.setText("تعديل الصنف: " + cat.getName());
            catCodeField.requestFocus();
        };


        addCatBtn.setOnAction(e -> run(() -> {
            String code = catCodeField.getText().trim();
            String name = catNameField.getText().trim();
            if (code.isEmpty()) throw new IllegalArgumentException("يرجى إدخال كود الصنف (حروف فقط).");
            if (name.isEmpty()) throw new IllegalArgumentException("يرجى إدخال اسم الصنف.");
            if (!code.matches("[A-Za-z\\u0600-\\u06FF]+")) throw new IllegalArgumentException("كود الصنف يجب أن يحتوي على حروف فقط.");
            String selectedUnits = unitCheckBoxes.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            String selectedMeas = measCheckBoxes.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            if (selectedUnits.isEmpty() || selectedMeas.isEmpty()) throw new IllegalArgumentException("يرجى اختيار وحدة ووحدة قياس واحدة على الأقل.");

            int eid = editingCategoryId[0];
            if (eid != -1) {
                // ── Edit mode: update existing category ──
                categoryService.updateCategory(eid, name, code, selectedUnits, selectedMeas);
                editingCategoryId[0] = -1;
                addCatBtn.setText("إضافة الصنف");
                addCatBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
                addCatTitle.setText("إضافة صنف جديد");
                catCodeField.clear(); catNameField.clear();
                unitCheckBoxes.forEach(cb -> cb.setSelected(false));
                measCheckBoxes.forEach(cb -> cb.setSelected(false));
                loadCategoryCards(categoryCardsBox, startEdit);
                reload();
                info("✅ تم تحديث الصنف \"" + name + "\" بنجاح.");
            } else {
                // ── Add mode: create new category ──
                categoryService.addCategory(name, code, selectedUnits, selectedMeas);
                catCodeField.clear(); catNameField.clear();
                unitCheckBoxes.forEach(cb -> cb.setSelected(false));
                measCheckBoxes.forEach(cb -> cb.setSelected(false));
                loadCategoryCards(categoryCardsBox, startEdit);
                reload();
                info("✅ تمت إضافة الصنف \"" + name + "\" بنجاح.");
            }
        }));
        
        // ── Inline edit callback — defined before addCatBtn so the lambda can reference it ──


        HBox addCatFormRow1 = new HBox(10, catCodeField, catNameField);
        addCatFormRow1.setAlignment(Pos.CENTER_LEFT);
        HBox addCatFormRow2 = new HBox(10, unitsBox, measBox);
        addCatFormRow2.setAlignment(Pos.CENTER_LEFT);
        VBox addCatCard = new VBox(10, addCatTitle, addCatFormRow1, addCatFormRow2, addCatBtn);
        addCatCard.setStyle(cardStyle);
        addCatCard.setPadding(new Insets(15));



        // ═══════════════════════════════════════════════════════════
        // SECTION: Reset System
        // ═══════════════════════════════════════════════════════════
        Label resetTitle = new Label("إعادة ضبط النظام");
        resetTitle.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#a73c3c;");
        Label resetDesc = new Label("تحذير: هذا الزر سيحذف جميع بيانات البرنامج نهائياً.");
        resetDesc.setWrapText(true);
        Button resetBtn = new Button("مسح جميع البيانات — Full System Reset");
        resetBtn.setStyle("-fx-background-color:#a73c3c;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        resetBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("⚠️ تحذير: مسح جميع البيانات");
            confirm.setHeaderText("سيتم حذف جميع بيانات النظام بشكل نهائي");
            confirm.setContentText("البيانات التي سيتم حذفها:\n"
                + "• المنتجات والمخزون\n"
                + "• العملاء وجميع معاملاتهم\n"
                + "• الموردين وجميع المشتريات\n"
                + "• المعاملات (مبيعات، مشتريات، مرتجعات)\n"
                + "• الخزنة وسجل الإيداع والسحب\n"
                + "• التقارير اليومية والشهرية والسنوية\n"
                + "• سجل الأرباح والأسعار\n"
                + "• العدد وسجل التأجير\n"
                + "• الأصناف والشركات\n"
                + "• إعدادات تخصيص الأعمدة\n"
                + "\n⚠️ هذا الإجراء لا يمكن التراجع عنه."
                + "\n\nهل أنت متأكد من رغبتك في مسح جميع البيانات؟");
            confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
                service.resetAllData();
                service.ensureTodayCard();
                loadCategoryCards(categoryCardsBox, startEdit);
                reload();
                info("✅ تم مسح جميع بيانات النظام بالكامل.");
            }));
        });
        VBox resetCard = new VBox(8, resetTitle, resetDesc, resetBtn);
        resetCard.setStyle(cardStyle);
        resetCard.setPadding(new Insets(15));

        VBox page = new VBox(18, pageTitle, addCatCard, new Label("الأصناف المسجلة:"), categoryCardsBox, new Separator(), resetCard);
        page.setPadding(new Insets(20));
        ScrollPane sp = new ScrollPane(page); sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");

        loadCategoryCards(categoryCardsBox, startEdit);
        // Auto-refresh category cards when switching tabs (no restart needed)
        refreshCallbacks.add(() -> loadCategoryCards(categoryCardsBox, startEdit));

        return tab("الإعدادات", sp);
    }

    /** Loads category cards into the given container. Each card has Edit and Delete buttons.
     *  @param editCallback if non-null, the Edit button populates the add form for inline editing */
    private void loadCategoryCards(VBox container, java.util.function.Consumer<Category> editCallback) {
        container.getChildren().clear();
        List<Category> allCats = categoryService.getAllCategories();
        if (allCats.isEmpty()) {
            Label emptyLbl = new Label("لا توجد تخصصات مسجلة.");
            emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:14px;");
            container.getChildren().add(emptyLbl);
            return;
        }
        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:14 18;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);";
        for (Category cat : allCats) {
            VBox card = new VBox(6);
            card.setStyle(cardStyle);

            String catName = cat.getName();
            if (catName == null || catName.trim().isEmpty()) catName = "—";
            Label nameLbl = new Label(catName);
            nameLbl.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            Label codeLbl = new Label("الكود: " + (cat.getCode() != null ? cat.getCode() : "—"));
            codeLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#1b7895;");
            Label unitsLbl = new Label("الوحدات: " + (cat.getUnits() != null && !cat.getUnits().isEmpty() ? cat.getUnits() : "—"));
            unitsLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
            Label measLbl = new Label("وحدات القياس: " + (cat.getMeasurementUnits() != null && !cat.getMeasurementUnits().isEmpty() ? cat.getMeasurementUnits() : "—"));
            measLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");

            // Edit button — populates the add form with this category's data (inline editing)
            Button editBtn = new Button("تعديل");
            editBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:11px;");
            if (editCallback != null) {
                editBtn.setOnAction(e -> editCallback.accept(cat));
            }

            // Delete button — uses a local copy of cat.getId() to avoid any lambda capture ambiguity
            final int targetCatId = cat.getId();
            final String targetCatName = cat.getName();
            Button deleteBtn = new Button("حذف");
            deleteBtn.setStyle("-fx-background-color:#a73c3c;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:11px;");
            deleteBtn.setOnAction(e -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "هل تريد حذف الصنف: " + targetCatName + " (" + targetCatId + ")؟", ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().filter(x -> x == ButtonType.YES).ifPresent(x -> run(() -> {
                    boolean deleted = categoryService.deleteCategory(targetCatId);
                    loadCategoryCards(container, editCallback);
                    reload();
                    if (deleted) {
                        info("تم حذف الصنف \"" + targetCatName + "\" نهائياً بنجاح.");
                    } else {
                        info("الصنف \"" + targetCatName + "\" مستخدم في منتجات حالية. تم إخفاؤه من القوائم مع الحفاظ على البيانات القديمة.");
                    }
                }));
            });

            HBox btnRow = new HBox(8, editBtn, deleteBtn);
            btnRow.setAlignment(Pos.CENTER_RIGHT);
            card.getChildren().addAll(nameLbl, codeLbl, unitsLbl, measLbl, btnRow);
            container.getChildren().add(card);
        }
    }

    private void showEditCategoryDialog(Category cat) {
        Stage dialog = new Stage();
        dialog.setTitle("تعديل الصنف: " + cat.getName());
        dialog.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        VBox root = new VBox(10);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color:#f7f9fc;");
        root.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        Label title = new Label("تعديل الصنف");
        title.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        TextField nameF = new TextField(cat.getName());
        nameF.setPromptText("اسم الصنف");
        nameF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        TextField codeF = new TextField(cat.getCode() != null ? cat.getCode() : "");
        codeF.setPromptText("كود الصنف");
        codeF.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);

        // Units checkboxes
        String[] unitOptions = {"بستلة", "زجاجة", "عبوة", "جالون", "جالون دوبل", "جركن", "قطعة", "مقاس", "طول", "الوزن"};
        VBox unitsEditBox = new VBox(4);
        unitsEditBox.setStyle("-fx-padding:8;-fx-background-color:white;-fx-background-radius:6;");
        Label unitsTitle = new Label("الوحدة:");
        unitsTitle.setStyle("-fx-font-weight:bold;");
        List<CheckBox> unitEditCBs = new ArrayList<>();
        for (String u : unitOptions) {
            CheckBox cb = new CheckBox(u);
            if (cat.getUnits() != null && cat.getUnits().contains(u)) cb.setSelected(true);
            unitEditCBs.add(cb);
        }
        unitsEditBox.getChildren().add(unitsTitle);
        unitsEditBox.getChildren().addAll(unitEditCBs);

        // Measurement units checkboxes
        String[] measOptions = {"كيلوجرام", "جرام", "لتر", "ميللي لتر", "متر", "عدد", "متر مسطح"};
        VBox measEditBox = new VBox(4);
        measEditBox.setStyle("-fx-padding:8;-fx-background-color:white;-fx-background-radius:6;");
        Label measTitle = new Label("وحدة القياس:");
        measTitle.setStyle("-fx-font-weight:bold;");
        List<CheckBox> measEditCBs = new ArrayList<>();
        for (String m : measOptions) {
            CheckBox cb = new CheckBox(m);
            if (cat.getMeasurementUnits() != null && cat.getMeasurementUnits().contains(m)) cb.setSelected(true);
            measEditCBs.add(cb);
        }
        measEditBox.getChildren().add(measTitle);
        measEditBox.getChildren().addAll(measEditCBs);

        Button saveBtn = new Button("حفظ التعديلات");
        saveBtn.setStyle("-fx-background-color:#27a068;-fx-background-radius:6;-fx-text-fill:white;-fx-font-weight:bold;");
        saveBtn.setOnAction(ev -> run(() -> {
            String selectedUnits = unitEditCBs.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            String selectedMeas = measEditCBs.stream().filter(cb -> cb.isSelected()).map(cb -> cb.getText()).reduce((a, b) -> a + "," + b).orElse("");
            categoryService.updateCategory(cat.getId(), nameF.getText(), codeF.getText(), selectedUnits, selectedMeas);
            dialog.close();
            reload();
            info("تم تحديث الصنف بنجاح");
        }));

        root.getChildren().addAll(title,
            new Label("اسم الصنف:"), nameF,
            new Label("كود الصنف:"), codeF,
            unitsEditBox, measEditBox, saveBtn);
        dialog.setScene(new Scene(root, 450, 500));
        dialog.show();
    }

    // ═══════════════════════════════════════════════════════════════
    // SHARED HELPERS
    // ═══════════════════════════════════════════════════════════════


    /** Display an archived daily report in a new window, parsing the JSON snapshot. */
    private void showArchivedReport(String archiveDate, String displayDate) {
        DailyReportArchive arch = service.dailyReportArchiveByDate(archiveDate);
        if (arch == null) { info("التقرير غير موجود."); return; }

        String snapshot = arch.snapshotData();
        double cardSales = 0, cardProfit = 0, cardWholesale = 0, cardBalance = 0, cardDeposit = 0, cardWithdrawal = 0;
        try {
            String cardSection = snapshot.substring(snapshot.indexOf("\"card\":{") + 8, snapshot.indexOf("},\"transactions"));
            cardSales = extractJsonDouble(cardSection, "sales");
            cardProfit = extractJsonDouble(cardSection, "profit");
            cardWholesale = extractJsonDouble(cardSection, "wholesale");
            cardBalance = extractJsonDouble(cardSection, "balance");
            cardDeposit = extractJsonDouble(cardSection, "deposit");
            cardWithdrawal = extractJsonDouble(cardSection, "withdrawal");
        } catch (Exception ignored) {}

        Stage reportStage = new Stage();
        reportStage.setTitle("التقرير المؤرشف - " + displayDate);
        VBox root = new VBox(15); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color:#f7f9fc;");
        Label header = new Label("التقرير اليومي - " + displayDate);
        header.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        VBox summaryBox = new VBox(8);
        summaryBox.setStyle("-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);");
        Label summaryTitle = new Label("ملخص اليوم");
        summaryTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        HBox statsRow = new HBox(20); statsRow.setAlignment(Pos.CENTER);
        double balance = cardSales - cardWholesale + cardDeposit - cardWithdrawal;
        statsRow.getChildren().addAll(
            createStatBox("المبيعات", money(cardSales), "#1b7895"),
            createStatBox("الربح", money(cardProfit), "#27a068"),
            createStatBox("الجملة", money(cardWholesale), "#c07030"),
            createStatBox("الإيداعات", money(cardDeposit), "#1b7895"),
            createStatBox("المسحوبات", money(cardWithdrawal), "#a73c3c"),
            createStatBox("الرصيد", money(balance), balance >= 0 ? "#27a068" : "#a73c3c"));
        summaryBox.getChildren().addAll(summaryTitle, statsRow);

        // Extract transactions from JSON
        List<java.util.Map<String, String>> txList = new java.util.ArrayList<>();
        try {
            int txArrStart = snapshot.indexOf("\"transactions\":[");
            if (txArrStart >= 0) {
                txArrStart += "\"transactions\":[".length();
                int txArrEnd = snapshot.indexOf("],\"customer_transactions");
                if (txArrEnd < 0) txArrEnd = snapshot.length() - 1;
                String txArrayContent = snapshot.substring(txArrStart, txArrEnd);
                int pos = 0;
                while (true) {
                    int objStart = txArrayContent.indexOf("{", pos);
                    if (objStart < 0) break;
                    int objEnd = findMatchingBrace(txArrayContent, objStart);
                    if (objEnd < 0) break;
                    String objStr = txArrayContent.substring(objStart, objEnd + 1);
                    java.util.Map<String, String> map = new java.util.HashMap<>();
                    map.put("type", extractJsonString(objStr, "type"));
                    map.put("product_name", extractJsonString(objStr, "product_name"));
                    map.put("quantity", String.valueOf(extractJsonDouble(objStr, "quantity")));
                    map.put("price", String.valueOf(extractJsonDouble(objStr, "price")));
                    map.put("profit", String.valueOf(extractJsonDouble(objStr, "profit")));
                    map.put("time", extractJsonString(objStr, "time"));
                    map.put("notes", extractJsonString(objStr, "notes"));
                    map.put("customer_name", extractJsonString(objStr, "customer_name"));
                    txList.add(map);
                    pos = objEnd + 1;
                }
            }
        } catch (Exception ignored) {}

        Label txTitle = new Label("المعاملات (" + txList.size() + ")");
        txTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        @SuppressWarnings("unchecked")
        TableView<java.util.Map<String, String>> table = new TableView<>();
        TableColumn<java.util.Map<String, String>, String>[] txCols = new TableColumn[]{
            column("الوقت", (java.util.Map<String, String> m) -> m.getOrDefault("time", "-")),
            column("النوع", (java.util.Map<String, String> m) -> m.getOrDefault("type", "-")),
            column("المنتج", (java.util.Map<String, String> m) -> { String pn = m.getOrDefault("product_name", ""); return pn.isEmpty() ? "-" : pn; }),
            column("الكمية / السعة", (java.util.Map<String, String> m) -> { double qv = Double.parseDouble(m.getOrDefault("quantity", "0")); return qv > 0 ? fmt(qv) : "-"; }),
            column("العميل", (java.util.Map<String, String> m) -> { String cn = m.getOrDefault("customer_name", ""); return cn.isEmpty() ? "-" : cn; }),
            column("السعر", (java.util.Map<String, String> m) -> money(Double.parseDouble(m.getOrDefault("price", "0")))),
            column("الربح", (java.util.Map<String, String> m) -> money(Double.parseDouble(m.getOrDefault("profit", "0")))),
            column("ملاحظات", (java.util.Map<String, String> m) -> m.getOrDefault("notes", "-"))
        };
        double[] txWidths = {80, 70, 160, 70, 130, 100, 100, 130};
        for (int i = 0; i < txCols.length && i < txWidths.length; i++) txCols[i].setPrefWidth(txWidths[i]);
        table.getColumns().addAll(txCols);
        table.setItems(FXCollections.observableArrayList(txList));
        table.setPrefHeight(300);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        root.getChildren().addAll(header, summaryBox, txTitle, table);
        reportStage.setScene(new Scene(root, 950, 600));
        reportStage.show();
    }

    /** Find the matching closing brace for a JSON object starting at pos. */
    private static int findMatchingBrace(String s, int openPos) {
        if (s.charAt(openPos) != '{') return -1;
        int depth = 1;
        for (int i = openPos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            if (c == '"') { i++; while (i < s.length() && s.charAt(i) != '"') { if (s.charAt(i) == '\\') i++; i++; } }
        }
        return -1;
    }

    /** Extract a string value from a JSON key-value pair. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { val.append(json.charAt(i + 1)); i++; continue; }
            if (c == '"') break;
            val.append(c);
        }
        return val.toString();
    }

    /** Extract a double value from a JSON key-value pair. */
    private static double extractJsonDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0;
        start += search.length();
        while (start < json.length() && json.charAt(start) == ' ') start++;
        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']' || c == ' ') break;
            val.append(c);
        }
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0; }
    }

    /** Display the monthly report with smart aggregation (summary + stats + category breakdown). */
    private void showMonthlyReportDetail(MonthlyReport mr) {
        Stage reportStage = new Stage();
        String ym = mr.yearMonth();
        String monthDisplay = ym;
        try {
            java.time.YearMonth ymParsed = java.time.YearMonth.parse(ym);
            String[] arabicMonths = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
            monthDisplay = arabicMonths[ymParsed.getMonthValue() - 1] + " " + ymParsed.getYear();
        } catch (Exception ignored) {}
        reportStage.setTitle("تقرير شهر " + monthDisplay);
        VBox root = new VBox(15); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color:#f7f9fc;");
        Label header = new Label("📊 التقرير الشهري - " + monthDisplay);
        header.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);";

        // ── Summary Box ──
        VBox summaryBox = new VBox(8); summaryBox.setStyle(cardStyle);
        Label summaryTitle = new Label("📈 ملخص الشهر"); summaryTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        double netMovement = mr.totalSales() - mr.totalWholesale() + mr.totalDeposit() - mr.totalWithdrawal();
        HBox statsRow = new HBox(20); statsRow.setAlignment(Pos.CENTER);
        statsRow.getChildren().addAll(
            createStatBox("إجمالي المبيعات", money(mr.totalSales()), "#1b7895"),
            createStatBox("إجمالي الربح", money(mr.totalProfit()), "#27a068"),
            createStatBox("إجمالي الجملة", money(mr.totalWholesale()), "#c07030"),
            createStatBox("إجمالي الإيداعات", money(mr.totalDeposit()), "#1b7895"),
            createStatBox("إجمالي المسحوبات", money(mr.totalWithdrawal()), "#a73c3c"),
            createStatBox("صافي الحركة", money(netMovement), netMovement >= 0 ? "#27a068" : "#a73c3c"));
        summaryBox.getChildren().addAll(summaryTitle, statsRow);

        // ── Statistical Indicators ──
        VBox statsBox = new VBox(8); statsBox.setStyle(cardStyle);
        Label statsTitle = new Label("📉 المؤشرات الإحصائية"); statsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        double avgSales = mr.daysCount() > 0 ? mr.totalSales() / mr.daysCount() : 0;
        double avgProfit = mr.daysCount() > 0 ? mr.totalProfit() / mr.daysCount() : 0;
        InventoryService.DailyCard bestDay = service.bestDayInMonth(ym);
        InventoryService.DailyCard worstDay = service.worstDayInMonth(ym);
        HBox indicatorRow = new HBox(20); indicatorRow.setAlignment(Pos.CENTER);
        indicatorRow.getChildren().addAll(
            createStatBox("عدد الأيام", String.valueOf(mr.daysCount()), "#7050a0"),
            createStatBox("متوسط المبيعات اليومي", money(avgSales), "#1b7895"),
            createStatBox("متوسط الربح اليومي", money(avgProfit), "#27a068"),
            createStatBox("رصيد الإغلاق", money(mr.closingBalance()), mr.closingBalance() >= 0 ? "#27a068" : "#a73c3c"));
        VBox bestWorstBox = new VBox(6);
        if (bestDay != null) {
            bestWorstBox.getChildren().add(new Label("🏆 أفضل يوم: " + bestDay.date() + " | مبيعات: " + money(bestDay.sales()) + " | ربح: " + money(bestDay.profit())) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#27a068;-fx-font-weight:bold;");
            }});
        }
        if (worstDay != null) {
            bestWorstBox.getChildren().add(new Label("⚠️ أقل يوم: " + worstDay.date() + " | مبيعات: " + money(worstDay.sales()) + " | ربح: " + money(worstDay.profit())) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#c07030;-fx-font-weight:bold;");
            }});
        }
        statsBox.getChildren().addAll(statsTitle, indicatorRow, bestWorstBox);

        // ── Category Aggregation Table ──
        List<InventoryService.MonthlyCategoryRow> catRows = service.monthlyCategoryTotals(ym);

        root.getChildren().addAll(header, summaryBox, statsBox);

        if (!catRows.isEmpty()) {
            VBox catBox = new VBox(8); catBox.setStyle(cardStyle);
            Label catTitle = new Label("📋 تفصيل المعاملات حسب النوع"); catTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            TableView<InventoryService.MonthlyCategoryRow> catTable = new TableView<>();
            TableColumn<InventoryService.MonthlyCategoryRow, String> catTypeCol = new TableColumn<>("النوع");
            catTypeCol.setCellValueFactory(v -> { String t = v.getValue().type(); return new javafx.beans.property.SimpleStringProperty(t != null ? t : ""); });
            TableColumn<InventoryService.MonthlyCategoryRow, String> catCountCol = new TableColumn<>("عدد العمليات");
            catCountCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(String.valueOf(v.getValue().count())));
            TableColumn<InventoryService.MonthlyCategoryRow, String> catTotalCol = new TableColumn<>("الإجمالي");
            catTotalCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().total())));
            TableColumn<InventoryService.MonthlyCategoryRow, String> catProfitCol = new TableColumn<>("الربح");
            catProfitCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().profit())));
            catTable.getColumns().addAll(catTypeCol, catCountCol, catTotalCol, catProfitCol);
            double[] catWidths = {130, 110, 140, 140};
            for (int i = 0; i < catWidths.length; i++) catTable.getColumns().get(i).setPrefWidth(catWidths[i]);
            attachColumnPrefs(catTable, "monthly_categories", 130, 110, 140, 140);
            catTable.setItems(FXCollections.observableArrayList(catRows));
            catTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            catTable.setPrefHeight(150);
            catBox.getChildren().addAll(catTitle, catTable);
            root.getChildren().add(catBox);
        }
        ScrollPane sp = new ScrollPane(root); sp.setFitToWidth(true);
        reportStage.setScene(new Scene(sp, 1000, 700)); reportStage.show();
    }

    /** Display the yearly report with smart aggregation (summary + stats + analytics). */
    private void showYearlyReportDetail(YearlyReport yr) {
        Stage reportStage = new Stage();
        int year = yr.year();
        reportStage.setTitle("تقرير سنة " + year);
        VBox root = new VBox(15); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color:#f7f9fc;");
        Label header = new Label("📊 التقرير السنوي - " + year);
        header.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        String cardStyle = "-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);";
        String[] arabicMonths = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};

        // ── 1. Summary Box ──
        VBox summaryBox = new VBox(8); summaryBox.setStyle(cardStyle);
        Label summaryTitle = new Label("📈 ملخص السنة"); summaryTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        int returnCount = service.yearlyReturnCount(year);
        double netMovement = yr.totalSales() - yr.totalWholesale() + yr.totalDeposit() - yr.totalWithdrawal();
        HBox statsRow = new HBox(20); statsRow.setAlignment(Pos.CENTER);
        statsRow.getChildren().addAll(
            createStatBox("المبيعات", money(yr.totalSales()), "#1b7895"),
            createStatBox("الربح", money(yr.totalProfit()), "#27a068"),
            createStatBox("الجملة", money(yr.totalWholesale()), "#c07030"),
            createStatBox("الإيداعات", money(yr.totalDeposit()), "#1b7895"),
            createStatBox("المسحوبات", money(yr.totalWithdrawal()), "#a73c3c"),
            createStatBox("المرتجعات", String.valueOf(returnCount), "#a73c3c"),
            createStatBox("صافي الحركة", money(netMovement), netMovement >= 0 ? "#27a068" : "#a73c3c"),
            createStatBox("عدد المعاملات", String.valueOf(yr.totalTransactions()), "#7050a0"));
        summaryBox.getChildren().addAll(summaryTitle, statsRow);

        // ── 2. Statistical Indicators ──
        VBox statsBox = new VBox(8); statsBox.setStyle(cardStyle);
        Label statsTitle = new Label("📉 المؤشرات الإحصائية"); statsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        double avgMonthlySales = yr.monthsCount() > 0 ? yr.totalSales() / yr.monthsCount() : 0;
        double avgMonthlyProfit = yr.monthsCount() > 0 ? yr.totalProfit() / yr.monthsCount() : 0;
        HBox indicatorRow = new HBox(20); indicatorRow.setAlignment(Pos.CENTER);
        indicatorRow.getChildren().addAll(
            createStatBox("عدد الأشهر", String.valueOf(yr.monthsCount()), "#7050a0"),
            createStatBox("متوسط المبيعات الشهري", money(avgMonthlySales), "#1b7895"),
            createStatBox("متوسط الربح الشهري", money(avgMonthlyProfit), "#27a068"),
            createStatBox("رصيد الإغلاق", money(yr.closingBalance()), yr.closingBalance() >= 0 ? "#27a068" : "#a73c3c"));

        // ── Best/Worst Month Info ──
        String bestSalesMonth = service.bestMonthBySales(year);
        String bestProfitMonth = service.bestMonthByProfit(year);
        String worstSalesMonth = service.worstMonthBySales(year);
        String worstProfitMonth = service.worstMonthByProfit(year);

        java.util.function.Function<String, String> fmtMonth = (String ym) -> {
            if (ym == null) return "—";
            try {
                int mn = Integer.parseInt(ym.split("-")[1]);
                return arabicMonths[mn - 1];
            } catch (Exception e) { return ym; }
        };
        VBox monthInfoBox = new VBox(6);
        if (bestSalesMonth != null) {
            monthInfoBox.getChildren().add(new Label("🏆 أفضل شهر مبيعات: " + fmtMonth.apply(bestSalesMonth)) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#1b7895;-fx-font-weight:bold;");
            }});
        }
        if (bestProfitMonth != null) {
            monthInfoBox.getChildren().add(new Label("🏆 أفضل شهر أرباح: " + fmtMonth.apply(bestProfitMonth)) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#27a068;-fx-font-weight:bold;");
            }});
        }
        if (worstSalesMonth != null) {
            monthInfoBox.getChildren().add(new Label("⚠️ أقل شهر مبيعات: " + fmtMonth.apply(worstSalesMonth)) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#c07030;-fx-font-weight:bold;");
            }});
        }
        if (worstProfitMonth != null) {
            monthInfoBox.getChildren().add(new Label("⚠️ أقل شهر أرباح: " + fmtMonth.apply(worstProfitMonth)) {{
                setStyle("-fx-font-size:12px;-fx-text-fill:#a73c3c;-fx-font-weight:bold;");
            }});
        }
        statsBox.getChildren().addAll(statsTitle, indicatorRow, monthInfoBox);

        root.getChildren().addAll(header, summaryBox, statsBox);

        // ── 3. Yearly Category Aggregation ──
        List<InventoryService.MonthlyCategoryRow> catRows = service.yearlyCategoryTotals(year);
        if (!catRows.isEmpty()) {
            VBox catBox = new VBox(8); catBox.setStyle(cardStyle);
            Label catTitle = new Label("📋 تفصيل المعاملات حسب النوع");
            catTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            TableView<InventoryService.MonthlyCategoryRow> catTable = new TableView<>();
            TableColumn<InventoryService.MonthlyCategoryRow, String> catTypeCol = new TableColumn<>("النوع");
            catTypeCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().type()));
            TableColumn<InventoryService.MonthlyCategoryRow, String> catCountCol = new TableColumn<>("عدد العمليات");
            catCountCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(String.valueOf(v.getValue().count())));
            TableColumn<InventoryService.MonthlyCategoryRow, String> catTotalCol = new TableColumn<>("الإجمالي");
            catTotalCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().total())));
            TableColumn<InventoryService.MonthlyCategoryRow, String> catProfitCol = new TableColumn<>("الربح");
            catProfitCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().profit())));
            catTable.getColumns().addAll(catTypeCol, catCountCol, catTotalCol, catProfitCol);
            double[] yearlyCatWidths = {130, 110, 140, 140};
            for (int i = 0; i < yearlyCatWidths.length; i++) catTable.getColumns().get(i).setPrefWidth(yearlyCatWidths[i]);
            attachColumnPrefs(catTable, "yearly_categories", 130, 110, 140, 140);
            catTable.setItems(FXCollections.observableArrayList(catRows));
            catTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            catTable.setPrefHeight(150);
            catBox.getChildren().addAll(catTitle, catTable);
            root.getChildren().add(catBox);
        }

        // ── 4. Analytics Sections ──
        VBox analyticsBox = new VBox(10);
        analyticsBox.setStyle(cardStyle);
        Label analyticsTitle = new Label("تحليلات السنة");
        analyticsTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        analyticsBox.getChildren().add(analyticsTitle);

        List<InventoryService.YearlyTopProduct> topProds = service.yearlyTopProducts(year);
        if (!topProds.isEmpty()) {
            Label tpLbl = new Label("أكثر المنتجات مبيعاً (" + topProds.size() + ")");
            tpLbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            TableView<InventoryService.YearlyTopProduct> tpTbl = new TableView<>();
            TableColumn<InventoryService.YearlyTopProduct, String> tpNameCol = new TableColumn<>("المنتج");
            tpNameCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().name()));
            TableColumn<InventoryService.YearlyTopProduct, String> tpQtyCol = new TableColumn<>("الكمية / السعة");
            tpQtyCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(fmt(v.getValue().quantity())));
            TableColumn<InventoryService.YearlyTopProduct, String> tpValCol = new TableColumn<>("القيمة");
            tpValCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().value())));
            tpTbl.getColumns().addAll(tpNameCol, tpQtyCol, tpValCol);
            tpTbl.setItems(FXCollections.observableArrayList(topProds));
            tpTbl.setPrefHeight(100); tpTbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            analyticsBox.getChildren().addAll(tpLbl, tpTbl);
        }

        List<InventoryService.YearlyTopPerson> topCusts = service.yearlyTopCustomers(year);
        if (!topCusts.isEmpty()) {
            Label tcLbl = new Label("أكثر العملاء شراءً (" + topCusts.size() + ")");
            tcLbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            TableView<InventoryService.YearlyTopPerson> tcTbl = new TableView<>();
            TableColumn<InventoryService.YearlyTopPerson, String> tcNameCol = new TableColumn<>("العميل");
            tcNameCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().name()));
            TableColumn<InventoryService.YearlyTopPerson, String> tcCntCol = new TableColumn<>("عدد المشتريات");
            tcCntCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(String.valueOf(v.getValue().count())));
            TableColumn<InventoryService.YearlyTopPerson, String> tcTotalCol = new TableColumn<>("الإجمالي");
            tcTotalCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().total())));
            tcTbl.getColumns().addAll(tcNameCol, tcCntCol, tcTotalCol);
            tcTbl.setItems(FXCollections.observableArrayList(topCusts));
            tcTbl.setPrefHeight(100); tcTbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            analyticsBox.getChildren().addAll(tcLbl, tcTbl);
        }

        List<InventoryService.YearlyTopPerson> topSups = service.yearlyTopSuppliers(year);
        if (!topSups.isEmpty()) {
            Label tsLbl = new Label("أكثر الموردين توريداً (" + topSups.size() + ")");
            tsLbl.setStyle("-fx-font-size:13px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            TableView<InventoryService.YearlyTopPerson> tsTbl = new TableView<>();
            TableColumn<InventoryService.YearlyTopPerson, String> tsNameCol = new TableColumn<>("المورد");
            tsNameCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(v.getValue().name()));
            TableColumn<InventoryService.YearlyTopPerson, String> tsCntCol = new TableColumn<>("عدد التوريدات");
            tsCntCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(String.valueOf(v.getValue().count())));
            TableColumn<InventoryService.YearlyTopPerson, String> tsTotalCol = new TableColumn<>("الإجمالي");
            tsTotalCol.setCellValueFactory(v -> new javafx.beans.property.SimpleStringProperty(money(v.getValue().total())));
            tsTbl.getColumns().addAll(tsNameCol, tsCntCol, tsTotalCol);
            tsTbl.setItems(FXCollections.observableArrayList(topSups));
            tsTbl.setPrefHeight(100); tsTbl.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            analyticsBox.getChildren().addAll(tsLbl, tsTbl);
        }

        if (analyticsBox.getChildren().size() > 1) root.getChildren().add(analyticsBox);

        ScrollPane sp = new ScrollPane(root); sp.setFitToWidth(true);
        reportStage.setScene(new Scene(sp, 1000, 750)); reportStage.show();
    }

    private void showToolsReportDetail() {
        List<InventoryService.RentalRecord> allRentals = service.getAllRentals();
        if (allRentals.isEmpty()) {
            info("لا توجد عمليات تأجير مسجلة.");
            return;
        }

        Stage reportStage = new Stage(); reportStage.setTitle("سجل تأجير العدد");
        VBox root = new VBox(15); root.setPadding(new Insets(20)); root.setStyle("-fx-background-color:#f7f9fc;");

        // ── Header ──
        Label header = new Label("سجل تأجير العدد");
        header.setStyle("-fx-font-size:20px;-fx-font-weight:bold;-fx-text-fill:#183b57;");

        // ── Total summary ──
        double totalIncome = allRentals.stream().mapToDouble(InventoryService.RentalRecord::price).sum();
        VBox summaryBox = new VBox(8);
        summaryBox.setStyle("-fx-background-color:white;-fx-background-radius:10;-fx-padding:15;-fx-effect:dropshadow(gaussian,#00000018,10,0,0,2);");
        Label summaryTitle = new Label("إجمالي التقارير");
        summaryTitle.setStyle("-fx-font-size:15px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
        HBox statsRow = new HBox(20); statsRow.setAlignment(Pos.CENTER);
        statsRow.getChildren().addAll(
            createStatBox("عدد التأجيرات", String.valueOf(allRentals.size()), "#7050a0"),
            createStatBox("إجمالي الإيراد", money(totalIncome), "#27a068")
        );
        summaryBox.getChildren().addAll(summaryTitle, statsRow);

        // ── Group by date ──
        java.util.Map<String, java.util.List<InventoryService.RentalRecord>> byDate = new java.util.LinkedHashMap<>();
        for (InventoryService.RentalRecord r : allRentals) {
            byDate.computeIfAbsent(r.date(), k -> new java.util.ArrayList<>()).add(r);
        }

        // ── Day sections container ──
        VBox daysContainer = new VBox(20);

        int[] dayCounter = {0};
        for (java.util.Map.Entry<String, java.util.List<InventoryService.RentalRecord>> entry : byDate.entrySet()) {
            String date = entry.getKey();
            java.util.List<InventoryService.RentalRecord> dayRentals = entry.getValue();

            // ── Day header ──
            String dayName = "";
            String displayDate = date;
            try {
                LocalDate ld = LocalDate.parse(date);
                dayName = ld.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("ar"));
                displayDate = ld.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception ignored) {}

            String detailTitleStr = dayName.isEmpty() ? displayDate : dayName + "  " + displayDate;
            double dayTotal = dayRentals.stream().mapToDouble(InventoryService.RentalRecord::price).sum();

            VBox daySection = new VBox(8);
            daySection.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:12;-fx-effect:dropshadow(gaussian,#00000012,6,0,0,2);");

            Label dayTitle = new Label(detailTitleStr + "  —  " + dayRentals.size() + " تأجيرات  —  إجمالي: " + money(dayTotal));
            dayTitle.setStyle("-fx-font-size:14px;-fx-font-weight:bold;-fx-text-fill:#1b7895;");

            // ── Table for this day ──
            TableView<InventoryService.RentalRecord> dayTable = new TableView<>();
            dayTable.setPrefHeight(Math.min(dayRentals.size() * 35 + 30, 250));
            dayTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            dayTable.setPlaceholder(new Label("لا توجد تأجيرات في هذا اليوم."));

            String[] cols = {"الوقت", "كود العدة", "اسم العدة", "الحجم", "اسم المستأجر", "سعر التأجير", "الربح", "الحالة"};
            for (int i = 0; i < cols.length; i++) {
                int x = i;
                TableColumn<InventoryService.RentalRecord, String> col = new TableColumn<>(cols[i]);
                col.setCellValueFactory(v -> new SimpleStringProperty(switch (x) {
                    case 0 -> v.getValue().time();
                    case 1 -> v.getValue().toolCode() != null && !v.getValue().toolCode().isEmpty() ? v.getValue().toolCode() : "—";
                    case 2 -> v.getValue().toolName();
                    case 3 -> v.getValue().size() != null && !v.getValue().size().isEmpty() ? v.getValue().size() : "—";
                    case 4 -> v.getValue().renterName() != null && !v.getValue().renterName().isEmpty() ? v.getValue().renterName() : "—";
                    case 5 -> money(v.getValue().price());
                    case 6 -> money(v.getValue().price());
                    default -> "تم تأجيرها".equals(v.getValue().status()) ? "تم تأجيرها" : (v.getValue().status() != null && !v.getValue().status().isEmpty() ? v.getValue().status() : "—");
                }));
                col.setPrefWidth(switch (x) {
                    case 0 -> 70; case 1 -> 80; case 2 -> 150;
                    case 3 -> 70; case 4 -> 120; case 5 -> 90;
                    case 6 -> 80; default -> 80;
                });
                dayTable.getColumns().add(col);
            }
            dayTable.setItems(FXCollections.observableArrayList(dayRentals));

            // ── Day summary footer ──
            HBox daySummaryRow = new HBox(15);
            daySummaryRow.setAlignment(Pos.CENTER_LEFT);
            daySummaryRow.getChildren().addAll(
                createStatBox("عدد التأجيرات", String.valueOf(dayRentals.size()), "#7050a0"),
                createStatBox("إجمالي الإيرادات", money(dayTotal), "#1b7895"),
                createStatBox("إجمالي الأرباح", money(dayTotal), "#27a068")
            );

            daySection.getChildren().addAll(dayTitle, dayTable, daySummaryRow);
            daysContainer.getChildren().add(daySection);

            dayCounter[0]++;
            // Limit to prevent too many pages
            if (dayCounter[0] >= 50) break;
        }

        root.getChildren().addAll(header, summaryBox, daysContainer);

        ScrollPane sp = new ScrollPane(root);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background:#f7f9fc; -fx-background-color:#f7f9fc;");
        reportStage.setScene(new Scene(sp, 1000, 700));
        reportStage.show();
    }

    private void showProductDetails(ProductRow p) {
        Alert dialog = new Alert(Alert.AlertType.INFORMATION); dialog.setTitle("تفاصيل المنتج"); dialog.setHeaderText(p.name());
        String un = p.unitName() != null && !p.unitName().isEmpty() ? p.unitName() : "—";
        String details = "الكود: " + p.code() + "\nالاسم: " + p.name() + "\nالشركة: " + (p.company().isEmpty() ? "—" : p.company())
            + "\nالصنف: " + p.category() + "\nالوحدة: " + un + "\nسعر البيع: " + money(p.sellingPrice())
            + "\nسعر الجملة: " + money(p.wholesalePrice()) + "\nالربح للوحدة: " + money(Math.max(0, p.sellingPrice() - p.wholesalePrice()))
            + "\nالكمية المتاحة: " + formatQty(p) + "\n";
        if ("PACKAGE".equals(p.unit()) && p.packageWeight() > 0) {
            String mu = p.measurementUnit() != null && !p.measurementUnit().isEmpty() ? p.measurementUnit() : "";
            double cap = p.packageWeight();
            if (!mu.isEmpty()) {
                details += "سعة العبوة: " + fmt(cap) + " " + mu + "\n";
            } else if (cap >= 1000) {
                details += "سعة العبوة: " + fmt(cap / 1000.0) + "\n";
            } else {
                details += "سعة العبوة: " + fmt(cap) + "\n";
            }
        }
        if (!p.notes().isEmpty()) details += "ملاحظات: " + p.notes() + "\n";
        TextArea ta = new TextArea(details); ta.setEditable(false); ta.setWrapText(true); ta.setPrefHeight(280); ta.setStyle("-fx-font-size:14px;");
        dialog.getDialogPane().setContent(ta); dialog.getDialogPane().setMinWidth(400); dialog.showAndWait();
    }

    private void doCash(String type, TextField personName, TextField amount, TextField note) {
        run(() -> {
            String pn = personName.getText() != null ? personName.getText().trim() : "";
            String nt = note.getText() != null ? note.getText().trim() : "";
            String message = service.cash(type, num(amount), nt, pn);
            personName.clear();
            amount.clear();
            note.clear();
            reload();
            String successMsg = "DEPOSIT".equals(type) ? "تم الإيداع بنجاح" : "تم السحب بنجاح";
            if (message != null) {
                info(successMsg + "\n" + message);
            } else {
                info(successMsg);
            }
        });
    }

    private VBox createStatBox(String label, String value, String color) {
        Label lbl = new Label(label); lbl.setStyle("-fx-font-size:10px;-fx-text-fill:#888;");
        Label val = new Label(value); val.setStyle("-fx-font-size:12px;-fx-font-weight:bold;-fx-text-fill:" + color + ";");
        VBox box = new VBox(2, lbl, val); box.setStyle("-fx-background-color:#f8f9fb;-fx-background-radius:4;-fx-padding:6 10;");
        return box;
    }

    /** Hides table columns where all values are empty/null/zero in the current dataset — dynamic columns. */
    private <T> void applyDynamicColumns(TableView<T> table, int... alwaysVisibleIndices) {
        List<T> items = table.getItems();
        if (items.isEmpty()) return;
        java.util.Set<Integer> alwaysVisible = new java.util.HashSet<>();
        for (int i : alwaysVisibleIndices) alwaysVisible.add(i);

        for (int i = 0; i < table.getColumns().size(); i++) {
            if (alwaysVisible.contains(i)) { table.getColumns().get(i).setVisible(true); continue; }
            boolean hasData = false;
            for (T item : items) {
                try {
                    var obs = table.getColumns().get(i).getCellObservableValue(item);
                    if (obs == null) continue;
                    Object val = obs.getValue();
                    String str = val != null ? val.toString() : "";
                    if (!str.isEmpty() && !str.equals("0.00 ج.م") && !str.equals("0") && !str.equals("—") && !str.equals("0.0") && !str.equals("0.00") && !str.equals("-")) {
                        hasData = true; break;
                    }
                } catch (Exception ignored) {}
            }
            table.getColumns().get(i).setVisible(hasData);
        }
    }

    private void reload() {
        reloadStock("");
        applyDynamicColumns(stockTable, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12); // show date/code/name/category/unit/qty/prices/supplier always
        salesTable.setItems(FXCollections.observableArrayList(service.recentSales()));
        applyDynamicColumns(salesTable, 0, 1, 2, 3, 4, 5, 6, 7); // all sale columns have data
        txTable.setItems(FXCollections.observableArrayList(service.todayTransactions()));
        reloadTools();
        refreshTodayRentals();
        applyDynamicColumns(txTable, 0, 1, 2, 3, 4, 5, 6); // always show date/type/product/qty/count/customer
        service.checkAndGenerateReports();
        var d = service.dashboard(); updateDateLabel();
        DailyCard today = service.ensureTodayCard();
        // Balance includes opening balance carried from previous days
        double balance = today.balance() + today.sales() - today.wholesale() + today.deposit() - today.withdrawal();
        // Cash operations breakdown: deposits and withdrawals
        // Update dashboard stat cards
        statProducts.setText(String.valueOf(d.products()));
        statSales.setText(money(d.todaySales()));
        statProfit.setText(money(d.todayProfit()));
        statWholesale.setText(money(d.todayPurchases()));
        statBalance.setText(money(balance));
        statTransactions.setText(String.valueOf(today.transactionCount()));
        statDeposit.setText(money(today.deposit()));
        statWithdrawal.setText(money(today.withdrawal()));
        // Update new customers card (improved design with cards)
        List<Customer> recentCustomers = service.allCustomers();
        newCustomersBox.getChildren().clear();
        if (recentCustomers.isEmpty()) {
            Label emptyLbl = new Label("لا يوجد عملاء بعد");
            emptyLbl.setStyle("-fx-text-fill:#aaa;-fx-font-size:13px;");
            newCustomersBox.getChildren().add(emptyLbl);
        } else {
            // Show last 5 customers with improved design
            int start = Math.max(0, recentCustomers.size() - 5);
            for (int i = recentCustomers.size() - 1; i >= start; i--) {
                Customer c = recentCustomers.get(i);
                HBox custRow = new HBox(8);
                custRow.setAlignment(Pos.CENTER_RIGHT);
                custRow.setStyle("-fx-background-color:#f8f9fb;-fx-background-radius:5;-fx-padding:6 10;" +
                    "-fx-border-color:#eaeef2;-fx-border-radius:5;-fx-border-width:1;");
                Label codeLbl = new Label(c.code());
                codeLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#1b7895;-fx-font-weight:bold;");
                Label nameLbl = new Label(c.name());
                nameLbl.setStyle("-fx-font-size:13px;-fx-text-fill:#333;");
                Label phoneLbl = new Label(c.phone() != null && !c.phone().isEmpty() ? c.phone() : "");
                phoneLbl.setStyle("-fx-font-size:11px;-fx-text-fill:#888;");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                custRow.getChildren().addAll(codeLbl, nameLbl, spacer, phoneLbl);
                newCustomersBox.getChildren().add(custRow);
            }
        }
        TreasurySummary ts = service.treasurySummary();
        // Use total balance including carried-forward opening balance (not just today's net)
        double totalTreasuryBalance = today.balance() + today.sales() - today.wholesale() + today.deposit() - today.withdrawal();
        treasuryToday.setText("رصيد الخزينة: " + money(totalTreasuryBalance) + "\nإجمالي المبيعات: " + money(ts.todaySales()) + "\nإجمالي الربح: " + money(ts.todayProfit()) + "\nإجمالي الجملة: " + money(ts.todayWholesale()));
        totalProfitLabel.setText(money(service.totalProfit()));

        refreshCustomerMaterials();
        loadCustomers();
        refreshCustomerReports();
        refreshSupplierReports();
        refreshDailyArchives.run();
        refreshMonthlyArchives.run();
        cashLedgerTable.setItems(FXCollections.observableArrayList(service.getAllCashLedger()));
        for (Runnable cb : refreshCallbacks) cb.run();
    }

    private void updateDateLabel() {
        LocalDate now = LocalDate.now();
        String dayName = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ar"));
        String monthName = now.getMonth().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ar"));
        homeDate.setText(dayName + " " + now.getDayOfMonth() + " " + monthName + " " + now.getYear());
    }

    private void reloadStock(String filter) { stockTable.setItems(FXCollections.observableArrayList(service.products(filter))); }

    private void refreshCustomerReports() {
        if (customerReportsBox == null) return;
        customerReportsBox.getChildren().clear();
        List<Customer> allCusts = service.allCustomers();
        if (allCusts.isEmpty()) {
            customerReportsBox.getChildren().add(new Label("لا يوجد عملاء مسجلين."));
            return;
        }
        for (Customer c : allCusts) {
            VBox card = new VBox(6);
            card.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:14;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);-fx-border-color:#d0d5dd;-fx-border-radius:8;");

            Label nameLbl = new Label(c.name());
            nameLbl.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            Label codeLbl = new Label("كود العميل: " + c.code());
            codeLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#1b7895;-fx-font-weight:bold;");

            String phone = c.phone() != null && !c.phone().isEmpty() ? c.phone() : "—";
            String addr = c.address() != null && !c.address().isEmpty() ? c.address() : "—";
            String mat = c.materials() != null && !c.materials().isEmpty() ? c.materials() : "—";
            String custType = c.customerType() != null && !c.customerType().isEmpty() ? c.customerType() : "—";

            Label phoneLbl = new Label("📞 " + phone);
            phoneLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
            Label addrLbl = new Label("📍 " + addr);
            addrLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
            Label typeLbl = new Label("🏷️ " + custType);
            typeLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#7050a0;-fx-font-weight:bold;");
            Label matLbl = new Label("📦 " + mat);
            matLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#1b7895;");

            HBox infoRow = new HBox(18, codeLbl, phoneLbl, typeLbl, addrLbl, matLbl);
            infoRow.setAlignment(Pos.CENTER_LEFT);

            TableView<CustomerTransaction> custTxTable = new TableView<>();
            custTxTable.setVisible(false); custTxTable.setManaged(false);
            custTxTable.setPrefHeight(200);
            custTxTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            custTxTable.setPlaceholder(new Label("لا توجد معاملات لهذا العميل."));
            @SuppressWarnings("unchecked")
            TableColumn<CustomerTransaction, String>[] txCols = new TableColumn[]{
                column("التاريخ", (CustomerTransaction t) -> t.dateTime() != null && t.dateTime().length() > 10 ? t.dateTime().substring(0, 10) : (t.dateTime() != null ? t.dateTime() : "")),
                column("النوع", CustomerTransaction::type),
                column("المنتج", (CustomerTransaction t) -> t.productName() != null ? t.productName() : "-"),
                column("الكمية / السعة", (CustomerTransaction t) -> { return "—"; }),
                column("العدد", (CustomerTransaction t) -> {
                    // CustomerTransaction doesn't have unit type; show quantity if it looks like whole units
                    double q = t.quantity();
                    if (q > 0 && q == Math.rint(q) && Math.abs(q) < 1e12) return String.format("%,d", (long) q);
                    return "—";
                }),
                column("السعر", (CustomerTransaction t) -> money(t.price())),
                column("الإجمالي", (CustomerTransaction t) -> money(t.total())),
                column("ملاحظات", (CustomerTransaction t) -> t.notes() != null ? t.notes() : "")};
            double[] txWidths = {90, 50, 140, 60, 45, 60, 70, 100};
            for (int i = 0; i < txCols.length && i < txWidths.length; i++) txCols[i].setPrefWidth(txWidths[i]);
            custTxTable.getColumns().addAll(txCols);
            attachColumnPrefs(custTxTable, "customer_tx", 90, 50, 140, 60, 45, 60, 70, 100);

            HBox statsRow = new HBox(15); statsRow.setAlignment(Pos.CENTER_LEFT);

            Button detailsBtn = new Button("عرض التفاصيل");
            detailsBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");
            Button pdfBtn = new Button("تصدير PDF");
            pdfBtn.setStyle("-fx-background-color:#7050a0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");

            Runnable loadFreshData = () -> {
                List<CustomerTransaction> freshTxs = service.customerTransactions(c.id());
                custTxTable.setItems(FXCollections.observableArrayList(freshTxs));
                double totalSales = freshTxs.stream().filter(t -> "بيع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
                double totalReturns = freshTxs.stream().filter(t -> "مرتجع".equals(t.type())).mapToDouble(CustomerTransaction::total).sum();
                statsRow.getChildren().setAll(
                    createStatBox("المعاملات", String.valueOf(freshTxs.size()), "#7050a0"),
                    createStatBox("إجمالي المشتريات", money(totalSales), "#1b7895"),
                    createStatBox("إجمالي المرتجعات", money(totalReturns), "#a73c3c")
                );
                pdfBtn.setOnAction(ev -> {
                    FileChooser fc = pdfChooser("تقرير_" + c.name() + ".pdf");
                    File file = fc.showSaveDialog(stage);
                    if (file != null) run(() -> { reports.exportCustomerDetailedPdf(file.toPath(), c, freshTxs); info("تم تصدير تقرير العميل بنجاح"); });
                });
            };
            loadFreshData.run();

            detailsBtn.setOnAction(e -> {
                boolean vis = custTxTable.isVisible();
                if (!vis) loadFreshData.run();
                custTxTable.setVisible(!vis); custTxTable.setManaged(!vis);
                detailsBtn.setText(vis ? "عرض التفاصيل" : " إخفاء التفاصيل");
            });

            pdfBtn.setOnAction(e -> {
                List<CustomerTransaction> freshTxs = service.customerTransactions(c.id());
                FileChooser fc = pdfChooser("تقرير_" + c.name() + ".pdf");
                File file = fc.showSaveDialog(stage);
                if (file != null) run(() -> { reports.exportCustomerDetailedPdf(file.toPath(), c, freshTxs); info("تم تصدير تقرير العميل بنجاح"); });
            });

            HBox btnRow = new HBox(10, detailsBtn, pdfBtn);
            btnRow.setAlignment(Pos.CENTER_LEFT);

            card.getChildren().addAll(nameLbl, infoRow, statsRow, btnRow, custTxTable);
            customerReportsBox.getChildren().add(card);
        }
    }

    private void refreshSupplierReports() {
        if (supplierReportsBox == null) return;
        supplierReportsBox.getChildren().clear();
        List<InventoryService.Supplier> allSupps = service.allSuppliers();
        if (allSupps.isEmpty()) {
            supplierReportsBox.getChildren().add(new Label("لا يوجد موردين مسجلين."));
            return;
        }
        for (InventoryService.Supplier s : allSupps) {
            VBox card = new VBox(6);
            card.setStyle("-fx-background-color:white;-fx-background-radius:8;-fx-padding:14;-fx-effect:dropshadow(gaussian,#00000015,8,0,0,2);-fx-border-color:#d0d5dd;-fx-border-radius:8;");

            Label nameLbl = new Label(s.name());
            nameLbl.setStyle("-fx-font-size:16px;-fx-font-weight:bold;-fx-text-fill:#183b57;");
            Label codeLbl = new Label("كود المورد: " + s.code());
            codeLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#1b7895;-fx-font-weight:bold;");

            String phone = s.phone() != null && !s.phone().isEmpty() ? s.phone() : "—";
            String specialty = s.specialty() != null && !s.specialty().isEmpty() ? s.specialty() : "—";
            String addr = s.address() != null && !s.address().isEmpty() ? s.address() : "—";

            Label phoneLbl = new Label("📞 " + phone);
            phoneLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");
            Label specLbl = new Label("🏷️ " + specialty);
            specLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#7050a0;-fx-font-weight:bold;");
            Label addrLbl = new Label("📍 " + addr);
            addrLbl.setStyle("-fx-font-size:12px;-fx-text-fill:#555;");

            HBox infoRow = new HBox(18, codeLbl, phoneLbl, specLbl, addrLbl);
            infoRow.setAlignment(Pos.CENTER_LEFT);

            TableView<InventoryService.SupplierTransaction> suppTxTable = new TableView<>();
            suppTxTable.setVisible(false); suppTxTable.setManaged(false);
            suppTxTable.setPrefHeight(200);
            suppTxTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            suppTxTable.setPlaceholder(new Label("لا توجد معاملات لهذا المورد."));
            @SuppressWarnings("unchecked")
            TableColumn<InventoryService.SupplierTransaction, String>[] txCols = new TableColumn[]{
                column("التاريخ", (InventoryService.SupplierTransaction t) -> t.dateTime() != null && t.dateTime().length() > 10 ? t.dateTime().substring(0, 10) : (t.dateTime() != null ? t.dateTime() : "")),
                column("النوع", (InventoryService.SupplierTransaction t) -> "IN".equals(t.type()) ? "وارد" : t.type()),
                column("المنتج", (InventoryService.SupplierTransaction t) -> t.productName() != null ? t.productName() : "-"),
                column("الكمية / السعة", (InventoryService.SupplierTransaction t) -> {
                    double pw = t.packageWeight();
                    // نعرض سعة الوحدة المسجلة وقت إضافة الوارد إذا كانت موجودة
                    if (pw > 0) {
                        return String.format("%,.2f", pw);
                    }
                    return "—";
                }),
                column("العدد", (InventoryService.SupplierTransaction t) -> {
                    double q = t.quantity();
                    String ut = t.unitType() != null ? t.unitType() : "COUNT";
                    if (("COUNT".equals(ut) || "PACKAGE".equals(ut)) && q > 0) return String.format("%,.0f", q);
                    return "—";
                }),
                column("السعر", (InventoryService.SupplierTransaction t) -> money(t.price())),
                column("الإجمالي", (InventoryService.SupplierTransaction t) -> money(t.total())),
                column("ملاحظات", (InventoryService.SupplierTransaction t) -> t.notes() != null ? t.notes() : "")};
            double[] txWidths = {90, 50, 140, 70, 45, 60, 70, 100};
            for (int i = 0; i < txCols.length && i < txWidths.length; i++) txCols[i].setPrefWidth(txWidths[i]);
            suppTxTable.getColumns().addAll(txCols);
            attachColumnPrefs(suppTxTable, "supplier_tx", 90, 50, 140, 70, 45, 60, 70, 100);

            HBox statsRow = new HBox(15); statsRow.setAlignment(Pos.CENTER_LEFT);

            Button detailsBtn = new Button("عرض التفاصيل");
            detailsBtn.setStyle("-fx-background-color:#1b7895;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");
            Button pdfBtn = new Button("تصدير PDF");
            pdfBtn.setStyle("-fx-background-color:#7050a0;-fx-background-radius:6;-fx-text-fill:white;-fx-font-size:12px;-fx-padding:6 16;-fx-font-weight:bold;");

            Runnable loadFreshData = () -> {
                List<InventoryService.SupplierTransaction> freshTxs = service.supplierTransactions(s.id());
                suppTxTable.setItems(FXCollections.observableArrayList(freshTxs));
                double totalPurchases = freshTxs.stream().mapToDouble(InventoryService.SupplierTransaction::total).sum();
                double supplierBalance = s.totalPurchaseValue(); // from Suppliers table
                statsRow.getChildren().setAll(
                    createStatBox("المعاملات", String.valueOf(freshTxs.size()), "#7050a0"),
                    createStatBox("إجمالي المشتريات", money(totalPurchases), "#1b7895"),
                    createStatBox("إجمالي المدفوعات", money(totalPurchases), "#c07030"),
                    createStatBox("الرصيد", "—", "#a73c3c")
                );
                pdfBtn.setOnAction(ev -> {
                    FileChooser fc = pdfChooser("تقرير_" + s.name() + ".pdf");
                    File file = fc.showSaveDialog(stage);
                    if (file != null) run(() -> { reports.exportSupplierPdf(file.toPath(), s, freshTxs); info("تم تصدير تقرير المورد بنجاح"); });
                });
            };
            loadFreshData.run();

            detailsBtn.setOnAction(e -> {
                boolean vis = suppTxTable.isVisible();
                if (!vis) loadFreshData.run();
                suppTxTable.setVisible(!vis); suppTxTable.setManaged(!vis);
                detailsBtn.setText(vis ? "عرض التفاصيل" : " إخفاء التفاصيل");
            });

            pdfBtn.setOnAction(e -> {
                List<InventoryService.SupplierTransaction> freshTxs = service.supplierTransactions(s.id());
                FileChooser fc = pdfChooser("تقرير_" + s.name() + ".pdf");
                File file = fc.showSaveDialog(stage);
                if (file != null) run(() -> { reports.exportSupplierPdf(file.toPath(), s, freshTxs); info("تم تصدير تقرير المورد بنجاح"); });
            });

            HBox btnRow = new HBox(10, detailsBtn, pdfBtn);
            btnRow.setAlignment(Pos.CENTER_LEFT);

            card.getChildren().addAll(nameLbl, infoRow, statsRow, btnRow, suppTxTable);
            supplierReportsBox.getChildren().add(card);
        }
    }

    private void exportDailyPdf() {
        FileChooser fc = pdfChooser("تقرير_اليوم.pdf"); File file = fc.showSaveDialog(stage);
        if (file != null) run(() -> { reports.exportDailyPdf(file.toPath()); info("تم تصدير التقرير بنجاح"); });
    }

    /** Filter that allows only digits and one dot/comma as decimal separator. */
    private static final UnaryOperator<TextFormatter.Change> NUMERIC_FILTER = c -> {
        String text = c.getControlNewText();
        if (text.isEmpty() || text.matches("\\d*[.,]?\\d*")) return c;
        return null;
    };

    private static TextField input(String hint) { TextField f = new TextField(); f.setPromptText(hint); f.setMaxWidth(220); f.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT); return f; }
    /** Creates a TextField that only accepts numeric input (digits + one dot/comma). */
    private static TextField numericInput(String hint) {
        TextField f = input(hint);
        f.setTextFormatter(new TextFormatter<>(NUMERIC_FILTER));
        return f;
    }
    private static double num(TextField f) { try { return NumberUtil.parseNumber(f.getText()); } catch (Exception e) { throw new IllegalArgumentException("أدخل رقمًا صحيحًا"); } }
    private static String money(double x) { return String.format("%.2f ج.م", x); }
    private static String fmt(double x) { return x == Math.rint(x) ? String.valueOf((int) x) : String.format("%.2f", x); }

    private static String formatQty(ProductRow p) {
        double qty = p.quantity();
        String mu = p.measurementUnit() != null ? p.measurementUnit().trim() : "";
        // PACKAGE: عرض سعة الوحدة (packageWeight) فقط — اللي اتسجلت في شاشة "إضافة الوارد"
        if ("PACKAGE".equals(p.unit()) && p.packageWeight() > 0) {
            double cap = p.packageWeight();
            if (!mu.isEmpty()) {
                return switch (mu) {
                    case "لتر" -> fmt(cap) + " لتر";
                    case "ميللي لتر" -> String.format("%.0f ميللي لتر", cap);
                    default -> fmt(cap) + " " + mu;
                };
            }
            return fmt(cap);
        }
        // باقي الأنواع: مفيش مفهوم "سعة وحدة" — يظهر شرطة
        return "—";
    }

    private static String unitLabel(String unit) { return switch (unit) { case "WEIGHT" -> "بالوزن"; case "PACKAGE" -> "عبوة"; default -> "بالقطعة"; }; } // ⚠ تُستخدم فقط كآخر حل احتياطي لمنتجات قديمة بلا بيانات
    private static Tab tab(String text, Node page) { return new Tab(text, page); }

    @SuppressWarnings("unchecked")
    private static <T> TableColumn<T, String> column(String title, java.util.function.Function<T, String> fn) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(x -> new SimpleStringProperty(fn.apply(x.getValue())));
        c.setPrefWidth(110); return c;
    }

    // ═══════════════════════════════════════════════════════════════
    // Column Preferences — Save/Load column order and width per table
    // ═══════════════════════════════════════════════════════════════
    private <T> void attachColumnPrefs(TableView<T> table, String tableKey, int... defaultWidths) {
        String[] saved = service.loadColumnPrefs(tableKey);
        if (saved != null && saved[0] != null && !saved[0].isEmpty()) {
            try {
                String[] orderParts = saved[0].split(",");
                String savedWidths = saved.length > 1 ? saved[1] : null;
                String[] widthParts = savedWidths != null && !savedWidths.isEmpty() ? savedWidths.split(",") : null;
                List<TableColumn<T, ?>> cols = new ArrayList<>(table.getColumns());
                // Reorder columns
                if (orderParts.length == cols.size()) {
                    for (int i = 0; i < orderParts.length; i++) {
                        int srcIdx = Integer.parseInt(orderParts[i]);
                        if (srcIdx >= 0 && srcIdx < cols.size() && srcIdx != i) {
                            TableColumn<T, ?> col = cols.get(srcIdx);
                            table.getColumns().remove(col);
                            table.getColumns().add(i, col);
                            cols = new ArrayList<>(table.getColumns());
                        }
                    }
                }
                // Apply saved widths
                if (widthParts != null && widthParts.length == table.getColumns().size()) {
                    for (int i = 0; i < widthParts.length && i < table.getColumns().size(); i++) {
                        try {
                            double w = NumberUtil.parseNumber(widthParts[i]);
                            if (w > 0) table.getColumns().get(i).setPrefWidth(w);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        } else if (defaultWidths != null && defaultWidths.length > 0) {
            for (int i = 0; i < defaultWidths.length && i < table.getColumns().size(); i++) {
                if (defaultWidths[i] > 0) table.getColumns().get(i).setPrefWidth(defaultWidths[i]);
            }
        }

        // ── Listen for column reorder (drag & drop) ──
        table.getColumns().addListener(new javafx.collections.ListChangeListener<>() {
            private boolean saving;
            @Override public void onChanged(javafx.collections.ListChangeListener.Change<? extends TableColumn<T, ?>> c) {
                if (saving) return;
                saving = true;
                Platform.runLater(() -> {
                    try {
                        StringBuilder order = new StringBuilder();
                        StringBuilder widths = new StringBuilder();
                        for (TableColumn<?, ?> col2 : table.getColumns()) {
                            int idx = col2.getTableView().getColumns().indexOf(col2);
                            if (order.length() > 0) order.append(",");
                            order.append(idx);
                            if (widths.length() > 0) widths.append(",");
                            widths.append((int) col2.getWidth());
                        }
                        service.saveColumnPrefs(tableKey, order.toString(), widths.toString());
                    } catch (Exception ignored) {}
                    saving = false;
                });
            }
        });

        // ── Listen for column width changes ──
        for (TableColumn<T, ?> col2 : table.getColumns()) {
            col2.widthProperty().addListener(new javafx.beans.value.ChangeListener<>() {
                private double lastW = -1;
                @Override public void changed(javafx.beans.value.ObservableValue<? extends Number> obs, Number old, Number w) {
                    double wv = w.doubleValue();
                    if (Math.abs(wv - lastW) < 1) return;
                    lastW = wv;
                    Platform.runLater(() -> {
                        try {
                            StringBuilder order = new StringBuilder();
                            StringBuilder widths = new StringBuilder();
                            for (TableColumn<?, ?> c3 : table.getColumns()) {
                                int idx = c3.getTableView().getColumns().indexOf(c3);
                                if (order.length() > 0) order.append(",");
                                order.append(idx);
                                if (widths.length() > 0) widths.append(",");
                                widths.append((int) c3.getWidth());
                            }
                            service.saveColumnPrefs(tableKey, order.toString(), widths.toString());
                        } catch (Exception ignored) {}
                    });
                }
            });
        }
    }

    private static void run(ThrowingRun r) { try { r.run(); } catch (Exception e) { error(e.getMessage()); } }
    private static void info(String msg) { new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK).showAndWait(); }
    private static void warn(String msg) { new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK).showAndWait(); }
    private static void error(String msg) { new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK).showAndWait(); }

    private FileChooser pdfChooser(String name) {
        FileChooser fc = new FileChooser(); fc.setTitle("حفظ الملف"); fc.setInitialFileName(name);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf")); return fc;
    }

    private static String css() {
        return ".root{-fx-font-family:'Segoe UI';-fx-font-size:14px;-fx-background-color:#f7f9fc;}"
            + ".top{-fx-background-color:linear-gradient(to left,#183b57,#276c87);-fx-padding:18;}"
            + ".top .label{-fx-text-fill:white;}.title{-fx-font-size:22px;-fx-font-weight:bold;}"
            + ".card{-fx-background-color:white;-fx-background-radius:12;-fx-padding:20;-fx-effect:dropshadow(gaussian,#00000018,12,0,0,3);-fx-font-size:15px;}"
            + ".button{-fx-background-radius:7;-fx-background-color:#1b7895;-fx-text-fill:white;-fx-font-weight:bold;-fx-padding:8 14;}"
            + ".tab-pane .tab-header-area .tab{-fx-padding:10 14;}.table-view{-fx-background-radius:10;}.table-view .column-header-background{-fx-background-color:#183b57;-fx-background-radius:10 10 0 0;}.table-view .column-header,.table-view .filler{-fx-background-color:#183b57;}.table-view .column-header .label{-fx-text-fill:white;-fx-font-weight:bold;-fx-font-size:13px;}";
    }

    public static void main(String[] args) {
        launch(args);
    }

    @FunctionalInterface
    private interface ThrowingRun { void run() throws Exception; }
}
