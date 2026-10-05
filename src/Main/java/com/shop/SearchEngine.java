package com.shop;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.collections.FXCollections;
import javafx.geometry.NodeOrientation;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Popup;

/**
 * محرك بحث موحد قابل لإعادة الاستخدام في جميع صفحات النظام.
 * <p>
 * المميزات:
 * <ul>
 *   <li>بحث لحظي (Live Search) — تظهر النتائج مع كل ضغطة على لوحة المفاتيح</li>
 *   <li>إكمال تلقائي (Auto Complete) — قائمة منبثقة بالنتائج المتطابقة</li>
 *   <li>تجاهل الأحرف الكبيرة والصغيرة (Case Insensitive)</li>
 *   <li>البحث بالاسم أو الكود</li>
 *   <li>البحث بجزء من الكلمة (Contains / LIKE %text%)</li>
 *   <li>سرعة عالية حتى مع آلاف السجلات (SQL LIKE مع LIMIT)</li>
 *   <li>رسالة "لا توجد نتائج مطابقة." عند عدم وجود نتائج</li>
 * </ul>
 * <p>
 * الاستخدام:
 * <pre>{@code
 * // تصفية جدول مباشرة
 * SearchEngine.attachTableFilter(searchField, query -> service.customersByCodeLike(query),
 *     customerTable, () -> service.allCustomers(), statusLabel);
 *
 * // Auto-Complete Popup
 * SearchEngine.attachAutoComplete(codeField, query -> service.customersByCodeLike(query),
 *     cust -> cust.code() + " | " + cust.name(),
 *     cust -> { codeField.setText(cust.code()); onSelect(cust); });
 * }</pre>
 */
public final class SearchEngine {

    private SearchEngine() {} // Utility class — no instances

    /** رسالة افتراضية عند عدم وجود نتائج */
    private static final String DEFAULT_NO_RESULTS = "لا توجد نتائج مطابقة.";

    // ═══════════════════════════════════════════════════════════════
    // 1. TABLE FILTER — TextField يصفّي TableView مباشرة
    // ═══════════════════════════════════════════════════════════════

    /**
     * يربط حقل بحث بـ TableView بحيث يتم تصفية الجدول مباشرة مع كل حرف يكتبه المستخدم.
     * <p>
     * مناسب لصفحات: العملاء، الموردين، المنتجات — أي صفحة بها جدول يحتاج تصفية سريعة.
     *
     * @param <T>           نوع العناصر في الجدول
     * @param field         حقل النص (TextField) الذي سيكتب فيه المستخدم
     * @param searchFn      دالة بحث تأخذ النص (String) وتعيد القائمة المصفاة (List&lt;T&gt;)
     * @param table         الجدول (TableView&lt;T&gt;) الذي سيتم تحديث بياناته
     * @param resetFn       دالة تعيد القائمة الكاملة (تُستدعى عند مسح حقل البحث)
     * @param statusLabel   Label لعرض "لا توجد نتائج مطابقة." (يمكن أن يكون null)
     * @return              نفس حقل النص (TextField) لتسهيل التسلسل
     */
    public static <T> TextField attachTableFilter(
            TextField field,
            Function<String, List<T>> searchFn,
            TableView<T> table,
            Supplier<List<T>> resetFn,
            Label statusLabel) {
        return attachTableFilter(field, searchFn, table, resetFn, statusLabel, DEFAULT_NO_RESULTS);
    }

    /**
     * {@link #attachTableFilter(TextField, Function, TableView, Supplier, Label)}
     * مع إمكانية تخصيص رسالة "لا توجد نتائج".
     */
    public static <T> TextField attachTableFilter(
            TextField field,
            Function<String, List<T>> searchFn,
            TableView<T> table,
            Supplier<List<T>> resetFn,
            Label statusLabel,
            String noResultsMessage) {

        if (field.getNodeOrientation() != NodeOrientation.RIGHT_TO_LEFT) {
            field.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        }

        field.textProperty().addListener((obs, o, n) -> {
            if (n == null || n.trim().isEmpty()) {
                table.setItems(FXCollections.observableArrayList(resetFn.get()));
                if (statusLabel != null) statusLabel.setText("");
                return;
            }
            String txt = n.trim();
            List<T> results = searchFn.apply(txt);
            table.setItems(FXCollections.observableArrayList(results));
            if (statusLabel != null) {
                statusLabel.setText(results.isEmpty() ? noResultsMessage : "");
            }
        });

        return field;
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. AUTO-COMPLETE POPUP — قائمة منبثقة بالنتائج مع الإكمال التلقائي
    // ═══════════════════════════════════════════════════════════════

    /**
     * نتيجة ربط AutoComplete — يحتفظ بمراجع الـ Popup والـ ListView
     * لإمكانية التحكم بهما خارجياً (مثلاً إخفاء القائمة يدوياً).
     */
    public static final class AutoCompleteResult<T> {
        private final Popup popup;
        private final ListView<T> listView;
        private final TextField field;

        AutoCompleteResult(Popup popup, ListView<T> listView, TextField field) {
            this.popup = popup;
            this.listView = listView;
            this.field = field;
        }

        /** إخفاء القائمة المنبثقة */
        public void hide() { popup.hide(); }

        /** إظهار القائمة المنبثقة */
        public void show() {
            var bounds = field.localToScreen(field.getBoundsInLocal());
            if (bounds != null) popup.show(field, bounds.getMinX(), bounds.getMaxY());
        }

        /** هل القائمة ظاهرة حالياً؟ */
        public boolean isShowing() { return popup.isShowing(); }

        /** الحصول على الـ ListView للتحكم المباشر */
        public ListView<T> getListView() { return listView; }
    }

    /**
     * يربط حقل نص بخدمة Auto Complete: يظهر Popuf مع اقتراحات أثناء الكتابة.
     * <p>
     * مناسب لصفحات: البيع (كود العميل)، الوارد (كود المورد)، وأي حقل يحتاج اقتراحات.
     *
     * @param <T>           نوع العناصر المقترحة
     * @param field         حقل النص (TextField)
     * @param searchFn      دالة بحث: String → List&lt;T&gt;
     * @param displayFn     دالة تحويل T → String (ما يظهر في القائمة)
     * @param onSelect      callback يُستدعى عند اختيار عنصر من القائمة (يُمرر العنصر المختار)
     * @param popupWidth    عرض القائمة المنبثقة (مثل 350)
     * @param popupHeight   ارتفاع القائمة المنبثقة (مثل 180)
     * @param statusLabel   Label اختياري لعرض "لا توجد نتائج مطابقة." (يمكن أن يكون null)
     * @param noResultsMsg  رسالة تظهر عند عدم وجود نتائج (يمكن أن تكون null للرسالة الافتراضية)
     * @return              AutoCompleteResult يحتوي على مراجع الـ Popup والـ ListView
     */
    public static <T> AutoCompleteResult<T> attachAutoComplete(
            TextField field,
            Function<String, List<T>> searchFn,
            Function<T, String> displayFn,
            Consumer<T> onSelect,
            double popupWidth,
            double popupHeight,
            Label statusLabel,
            String noResultsMsg) {

        if (field.getNodeOrientation() != NodeOrientation.RIGHT_TO_LEFT) {
            field.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        }

        ListView<T> suggestions = new ListView<>();
        suggestions.setPrefHeight(popupHeight);
        suggestions.setPrefWidth(popupWidth);

        Popup popup = new Popup();
        popup.getContent().add(suggestions);
        popup.setAutoHide(true);

        String noResMsg = (noResultsMsg != null) ? noResultsMsg : DEFAULT_NO_RESULTS;

        field.textProperty().addListener((obs, o, n) -> {
            if (n == null || n.trim().isEmpty()) {
                popup.hide();
                if (statusLabel != null) statusLabel.setText("");
                return;
            }
            String txt = n.trim();
            List<T> results = searchFn.apply(txt);

            if (!results.isEmpty()) {
                suggestions.setItems(FXCollections.observableArrayList(results));
                suggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(T item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : displayFn.apply(item));
                    }
                });
                if (!popup.isShowing()) {
                    var bounds = field.localToScreen(field.getBoundsInLocal());
                    if (bounds != null) popup.show(field, bounds.getMinX(), bounds.getMaxY());
                }
                if (statusLabel != null) statusLabel.setText("");
            } else {
                popup.hide();
                if (statusLabel != null) statusLabel.setText(noResMsg);
            }
        });

        suggestions.setOnMouseClicked(ev -> {
            T sel = suggestions.getSelectionModel().getSelectedItem();
            if (sel != null) {
                popup.hide();
                onSelect.accept(sel);
            }
        });

        return new AutoCompleteResult<>(popup, suggestions, field);
    }

    /**
     * نسخة مبسّطة من {@link #attachAutoComplete(TextField, Function, Function, Consumer, double, double, Label, String)}
     * بدون statusLabel ولا noResultsMsg (عرض وارتفاع افتراضيان 350x180).
     */
    public static <T> AutoCompleteResult<T> attachAutoComplete(
            TextField field,
            Function<String, List<T>> searchFn,
            Function<T, String> displayFn,
            Consumer<T> onSelect) {
        return attachAutoComplete(field, searchFn, displayFn, onSelect, 350, 180, null, null);
    }

    /**
     * نسخة مع statusLabel (اختياري) لعرض رسالة عدم وجود نتائج.
     */
    public static <T> AutoCompleteResult<T> attachAutoComplete(
            TextField field,
            Function<String, List<T>> searchFn,
            Function<T, String> displayFn,
            Consumer<T> onSelect,
            Label statusLabel) {
        return attachAutoComplete(field, searchFn, displayFn, onSelect, 350, 180, statusLabel, null);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. MULTI-SOURCE AUTO-COMPLETE — يدمج نتائج من مصادر متعددة
    // ═══════════════════════════════════════════════════════════════

    /**
     * مصدر بحث — يُستخدم في البحث متعدد المصادر (مثل الهيدر).
     *
     * @param <T> نوع العناصر في هذا المصدر
     */
    public record SearchSource<T>(
            String label,              // تصنيف المصدر (مثل "منتج", "عميل", "مورد")
            String iconEmoji,          // أيقونة (مثل "📦", "👤", "📍")
            Function<String, List<T>> searchFn,
            Function<T, String> displayFn,
            Consumer<T> onSelect
    ) {}

    /**
     * يربط حقل نص بعدة مصادر بحث ويجمع النتائج في قائمة واحدة.
     * عند اختيار عنصر، يتم استدعاء onSelect الخاص بالمصدر الذي ينتمي إليه العنصر.
     * <p>
     * مناسب لـ: شريط البحث العلوي (Header) الذي يبحث في المنتجات والعملاء والموردين معاً.
     *
     * @param field         حقل النص
     * @param sources       قائمة بمصادر البحث (SearchSource)
     * @param displayWidth  عرض القائمة المنبثقة
     * @param displayHeight ارتفاع القائمة المنبثقة
     * @return              AutoCompleteResult للتحكم في Popup/ListView
     */
    public static AutoCompleteResult<String> attachMultiSourceSearch(
            TextField field,
            List<SearchSource<?>> sources,
            double displayWidth,
            double displayHeight) {

        if (field.getNodeOrientation() != NodeOrientation.RIGHT_TO_LEFT) {
            field.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        }

        ListView<String> suggestions = new ListView<>();
        suggestions.setPrefHeight(displayHeight);
        suggestions.setPrefWidth(displayWidth);

        Popup popup = new Popup();
        popup.getContent().add(suggestions);
        popup.setAutoHide(true);

        // تخزين نوع كل عنصر في القائمة (لربط onSelect الصحيح)
        final class SearchHit {
            final int sourceIndex;
            final Object item;

            SearchHit(int sourceIndex, Object item) {
                this.sourceIndex = sourceIndex;
                this.item = item;
            }
        }

        field.textProperty().addListener((obs, o, n) -> {
            if (n == null || n.trim().isEmpty()) {
                popup.hide();
                return;
            }
            String txt = n.trim();

            // جمع النتائج من جميع المصادر
            java.util.ArrayList<SearchHit> allHits = new java.util.ArrayList<>();
            java.util.ArrayList<String> displayItems = new java.util.ArrayList<>();

            for (int si = 0; si < sources.size(); si++) {
                SearchSource<?> source = sources.get(si);
                List<?> results = source.searchFn().apply(txt);
                if (!results.isEmpty() && !displayItems.isEmpty()) {
                    // فاصل بين المجموعات
                    String separator = "─── " + source.iconEmoji() + " " + source.label() + " ───";
                    allHits.add(new SearchHit(-1, null)); // separator marker
                    displayItems.add(separator);
                } else if (!results.isEmpty() && displayItems.isEmpty() && sources.size() > 1) {
                    String separator = "─── " + source.iconEmoji() + " " + source.label() + " ───";
                    allHits.add(new SearchHit(-1, null));
                    displayItems.add(separator);
                }
                for (Object result : results) {
                    @SuppressWarnings("unchecked")
                    Function<Object, String> objDisplayFn = (Function<Object, String>) source.displayFn();
                    String display = source.iconEmoji() + " " + objDisplayFn.apply(result);
                    allHits.add(new SearchHit(si, result));
                    displayItems.add(display);
                }
            }

            if (!allHits.isEmpty()) {
                // تخزين الـ hits للوصول إليها عند النقر
                suggestions.setUserData(allHits);
                suggestions.setItems(FXCollections.observableArrayList(displayItems));
                suggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            setStyle("");
                        } else {
                            setText(item);
                            if (item.startsWith("───")) {
                                setStyle("-fx-text-fill:#888;-fx-font-style:italic;-fx-font-size:11px;");
                            } else {
                                setStyle("");
                            }
                        }
                    }
                });
                if (!popup.isShowing()) {
                    var bounds = field.localToScreen(field.getBoundsInLocal());
                    if (bounds != null) popup.show(field, bounds.getMinX(), bounds.getMaxY());
                }
            } else {
                // لا توجد نتائج — اعرض رسالة
                suggestions.setUserData(new java.util.ArrayList<SearchHit>());
                suggestions.setItems(FXCollections.observableArrayList("لا توجد نتائج مطابقة."));
                suggestions.setCellFactory(lv -> new ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item);
                        setStyle(empty || item == null ? "" : "-fx-text-fill:#999;-fx-font-style:italic;");
                    }
                });
                if (!popup.isShowing()) {
                    var bounds = field.localToScreen(field.getBoundsInLocal());
                    if (bounds != null) popup.show(field, bounds.getMinX(), bounds.getMaxY());
                }
            }
        });

        suggestions.setOnMouseClicked(ev -> {
            int idx = suggestions.getSelectionModel().getSelectedIndex();
            if (idx < 0) return;
            @SuppressWarnings("unchecked")
            java.util.ArrayList<SearchHit> hits = (java.util.ArrayList<SearchHit>) suggestions.getUserData();
            if (hits == null || idx >= hits.size()) return;
            SearchHit hit = hits.get(idx);
            if (hit.sourceIndex < 0 || hit.sourceIndex >= sources.size()) return; // separator or no-results
            popup.hide();
            field.clear();
            SearchSource<?> source = sources.get(hit.sourceIndex);
            @SuppressWarnings("unchecked")
            Consumer<Object> onSelect = (Consumer<Object>) source.onSelect();
            onSelect.accept(hit.item);
        });

        return new AutoCompleteResult<>(popup, suggestions, field);
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. FACTORY METHODS — إنشاء حقل بحث جاهز
    // ═══════════════════════════════════════════════════════════════

    /**
     * إنشاء حقل نص جاهز للبحث مع التنسيق الأساسي.
     *
     * @param promptText النص التوجيهي (مثل "بحث بالكود أو الاسم...")
     * @param prefWidth  العرض المفضل
     * @return TextField مهيأ للبحث
     */
    public static TextField createSearchField(String promptText, double prefWidth) {
        TextField field = new TextField();
        field.setPromptText(promptText);
        field.setPrefWidth(prefWidth);
        field.setNodeOrientation(NodeOrientation.RIGHT_TO_LEFT);
        return field;
    }

    /**
     * إنشاء حقل بحث جاهز مع ربطه بتصفية جدول مباشرة.
     * تجمع بين {@link #createSearchField(String, double)} و {@link #attachTableFilter}.
     */
    public static <T> TextField createTableFilter(
            String promptText,
            double prefWidth,
            Function<String, List<T>> searchFn,
            TableView<T> table,
            Supplier<List<T>> resetFn,
            Label statusLabel) {
        TextField field = createSearchField(promptText, prefWidth);
        return attachTableFilter(field, searchFn, table, resetFn, statusLabel);
    }

    /**
     * إنشاء حقل بحث جاهز مع Auto Complete.
     * تجمع بين {@link #createSearchField(String, double)} و {@link #attachAutoComplete}.
     */
    public static <T> AutoCompleteResult<T> createAutoCompleteField(
            String promptText,
            double prefWidth,
            Function<String, List<T>> searchFn,
            Function<T, String> displayFn,
            Consumer<T> onSelect) {
        TextField field = createSearchField(promptText, prefWidth);
        return attachAutoComplete(field, searchFn, displayFn, onSelect);
    }
}
