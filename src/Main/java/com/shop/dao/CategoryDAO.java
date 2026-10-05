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
import com.shop.model.Category;


public class CategoryDAO {



    // إضافة نوع / تصنيف
    public void insert(Category category) {


        String sql = """
        
        INSERT INTO Categories(name, code, units, measurement_units)
        VALUES(?,?,?,?)

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, category.getName());
            stmt.setString(2, category.getCode() != null ? category.getCode() : "");
            stmt.setString(3, category.getUnits() != null ? category.getUnits() : "");
            stmt.setString(4, category.getMeasurementUnits() != null ? category.getMeasurementUnits() : "");


            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في إضافة تصنيف", e);
        }

    }



    // جلب كل التصنيفات
    public List<Category> findAll(){


        List<Category> categories = new ArrayList<>();


        String sql = """
        
        SELECT *
        FROM Categories
        WHERE is_active = 1 OR is_active IS NULL
        ORDER BY name

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ){


            while(rs.next()){


                Category category = new Category();


                category.setId(
                        rs.getInt("id")
                );


                category.setName(
                        rs.getString("name")
                );

                try {
                    category.setCode(rs.getString("code"));
                } catch (Exception ignored) {}
                try {
                    category.setUnits(rs.getString("units"));
                } catch (Exception ignored) {}
                try {
                    category.setMeasurementUnits(rs.getString("measurement_units"));
                } catch (Exception ignored) {}


                categories.add(category);


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب التصنيفات", e);
        }

        return categories;

    }



    // البحث بالاسم (يشمل الفعال وغير الفعال لمنع إضافة مكرر)
    public Category findByName(String name){


        String sql = """

        SELECT *
        FROM Categories
        WHERE name = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1,name);


            ResultSet rs = stmt.executeQuery();


            if(rs.next()){


                Category category = new Category();


                category.setId(
                        rs.getInt("id")
                );


                category.setName(
                        rs.getString("name")
                );


                return category;

            }


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في البحث عن تصنيف", e);
        }

        return null;

    }



    // تعديل التصنيف
    public void update(Category category){


        String sql = """
        UPDATE Categories
        SET name = ?, code = ?, units = ?, measurement_units = ?
        WHERE id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, category.getName());
            stmt.setString(2, category.getCode() != null ? category.getCode() : "");
            stmt.setString(3, category.getUnits() != null ? category.getUnits() : "");
            stmt.setString(4, category.getMeasurementUnits() != null ? category.getMeasurementUnits() : "");
            stmt.setInt(5, category.getId());


            stmt.executeUpdate();


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في تحديث التصنيف", e);
        }

    }



    // حذف التصنيف
    public void delete(int id){


        String sql = """

        DELETE FROM Categories
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

            e.printStackTrace();

        }


    }


}