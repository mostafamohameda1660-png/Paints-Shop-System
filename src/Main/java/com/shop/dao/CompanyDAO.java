package com.shop.dao;


import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.Company;


public class CompanyDAO {


    // إضافة شركة
    public void insert(Company company) {


        String sql = """
        
        INSERT INTO Companies(name)
        VALUES(?)

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, company.getName());

            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في إضافة شركة", e);
        }

    }



    // جلب كل الشركات
    public List<Company> findAll(){


        List<Company> companies = new ArrayList<>();


        String sql = "SELECT * FROM Companies ORDER BY name";


        try(
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ){


            while(rs.next()){


                Company company = new Company();


                company.setId(
                        rs.getInt("id")
                );


                company.setName(
                        rs.getString("name")
                );


                companies.add(company);


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب الشركات", e);
        }

        return companies;

    }



    // البحث عن شركة بالاسم
    public Company findByName(String name){


        String sql = """
        
        SELECT *
        FROM Companies
        WHERE name = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, name);


            ResultSet rs = stmt.executeQuery();


            if(rs.next()){


                Company company = new Company();


                company.setId(
                        rs.getInt("id")
                );


                company.setName(
                        rs.getString("name")
                );


                return company;

            }


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في البحث عن شركة", e);
        }

        return null;

    }



    // تعديل اسم الشركة
    public void update(Company company){


        String sql = """
        
        UPDATE Companies
        SET name = ?
        WHERE id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, company.getName());

            stmt.setInt(2, company.getId());


            stmt.executeUpdate();


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في تحديث الشركة", e);
        }

    }



    // حذف شركة
    public void delete(int id){


        String sql = """

        DELETE FROM Companies
        WHERE id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setInt(1,id);

            stmt.executeUpdate();


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في حذف الشركة", e);
        }


    }


}