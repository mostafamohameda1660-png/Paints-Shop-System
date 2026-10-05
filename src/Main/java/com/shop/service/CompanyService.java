package com.shop.service;

import java.util.List;

import com.shop.dao.CompanyDAO;
import com.shop.exception.ValidationException;
import com.shop.model.Company;
import com.shop.utils.ValidationUtil;

public class CompanyService {

    private final CompanyDAO companyDAO = new CompanyDAO();

    // إضافة شركة
    public void addCompany(String name) {

        if (ValidationUtil.isNullOrEmpty(name)) {
            throw new ValidationException("اسم الشركة مطلوب.");
        }

        if (companyDAO.findByName(name) != null) {
            throw new ValidationException("هذه الشركة موجودة بالفعل.");
        }

        Company company = new Company();
        company.setName(name.trim());

        companyDAO.insert(company);
    }

    // عرض كل الشركات
    public List<Company> getAllCompanies() {
        return companyDAO.findAll();
    }

    // تعديل شركة
    public void updateCompany(int id, String name) {

        if (ValidationUtil.isNullOrEmpty(name)) {
            throw new ValidationException("اسم الشركة مطلوب.");
        }

        Company company = new Company();
        company.setId(id);
        company.setName(name.trim());

        companyDAO.update(company);
    }

    // حذف شركة
    public void deleteCompany(int id) {
        companyDAO.delete(id);
    }

}