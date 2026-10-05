package com.shop.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import com.shop.dao.CategoryDAO;
import com.shop.database.DatabaseManager;
import com.shop.exception.ValidationException;
import com.shop.model.Category;
import com.shop.utils.ValidationUtil;

public class CategoryService {

    private final CategoryDAO categoryDAO = new CategoryDAO();

    // إضافة تصنيف
    public void addCategory(String name, String code, String units, String measurementUnits) {

        if (ValidationUtil.isNullOrEmpty(name)) {
            throw new ValidationException("اسم التصنيف مطلوب.");
        }

        if (categoryDAO.findByName(name) != null) {
            throw new ValidationException("هذا التصنيف موجود بالفعل.");
        }

        Category category = new Category();
        category.setName(name.trim());
        category.setCode(code != null ? code.trim() : "");
        category.setUnits(units != null ? units : "");
        category.setMeasurementUnits(measurementUnits != null ? measurementUnits : "");

        categoryDAO.insert(category);
    }

    // عرض كل التصنيفات النشطة
    public List<Category> getAllCategories() {
        return categoryDAO.findAll();
    }

    // تعديل التصنيف
    public void updateCategory(int id, String name, String code, String units, String measurementUnits) {

        if (ValidationUtil.isNullOrEmpty(name)) {
            throw new ValidationException("اسم التصنيف مطلوب.");
        }

        // Get old code to propagate changes to products
        Category oldCategory = getCategoryById(id);
        String oldCode = oldCategory != null ? oldCategory.getCode() : "";

        Category category = new Category();
        category.setId(id);
        category.setName(name.trim());
        category.setCode(code != null ? code.trim() : "");
        category.setUnits(units != null ? units : "");
        category.setMeasurementUnits(measurementUnits != null ? measurementUnits : "");

        categoryDAO.update(category);

        // Propagate code change to all products under this category
        if (oldCode != null && !oldCode.isEmpty() && code != null && !code.trim().isEmpty() && !oldCode.equals(code.trim())) {
            propagateCodeChange(id, oldCode, code.trim());
        }
    }

    /**
     * حذف الصنف — إذا لم يكن مستخدماً في أي منتج يُحذف نهائياً.
     * إذا كان مستخدماً يتم إخفاؤه (is_active=0) مع الحفاظ على البيانات القديمة.
     * @return true إذا تم الحذف النهائي، false إذا تم الإخفاء فقط
     */
    public boolean deleteCategory(int id) {
        // التحقق من وجود منتجات مرتبطة بهذا الصنف
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT count(*) FROM Products WHERE category_id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (r.next() && r.getInt(1) > 0) {
                    // الصنف مستخدم — إخفاؤه بدلاً من حذفه
                    try (PreparedStatement u = c.prepareStatement("UPDATE Categories SET is_active=0 WHERE id=?")) {
                        u.setInt(1, id);
                        u.executeUpdate();
                    }
                    return false; // تم الإخفاء فقط
                }
            }
        } catch (SQLException e) {
            throw new ValidationException("خطأ في التحقق من استخدامات الصنف: " + e.getMessage());
        }
        // الصنف غير مستخدم — حذف نهائي
        categoryDAO.delete(id);
        return true;
    }

    // جلب تصنيف بالرقم (يشمل الفعال وغير الفعال للتوافق مع البيانات القديمة)
    public Category getCategoryById(int id) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM Categories WHERE id=?")) {
            s.setInt(1, id);
            try (ResultSet r = s.executeQuery()) {
                if (r.next()) {
                    Category cat = new Category();
                    cat.setId(r.getInt("id"));
                    cat.setName(r.getString("name"));
                    try { cat.setCode(r.getString("code")); } catch (Exception ignored) {}
                    try { cat.setUnits(r.getString("units")); } catch (Exception ignored) {}
                    try { cat.setMeasurementUnits(r.getString("measurement_units")); } catch (Exception ignored) {}
                    return cat;
                }
            }
        } catch (SQLException e) {
            throw new ValidationException("خطأ في جلب بيانات الصنف: " + e.getMessage());
        }
        return null;
    }

    // Propagate code change to all products under this category
    private void propagateCodeChange(int categoryId, String oldCode, String newCode) {
        try (Connection c = DatabaseManager.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Get all products under this category
                try (PreparedStatement s = c.prepareStatement("SELECT id, code FROM Products WHERE category_id = ? AND is_active = 1")) {
                    s.setInt(1, categoryId);
                    try (ResultSet r = s.executeQuery()) {
                        while (r.next()) {
                            int productId = r.getInt(1);
                            String oldProductCode = r.getString(2);
                            if (oldProductCode != null && oldProductCode.startsWith(oldCode)) {
                                String serial = oldProductCode.substring(oldCode.length());
                                String newProductCode = newCode + serial;
                                try (PreparedStatement u = c.prepareStatement("UPDATE Products SET code = ? WHERE id = ?")) {
                                    u.setString(1, newProductCode);
                                    u.setInt(2, productId);
                                    u.executeUpdate();
                                }
                            }
                        }
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new ValidationException("خطأ في تحديث أكواد المنتجات: " + e.getMessage());
        }
    }
}