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
import com.shop.model.Product;


public class ProductDAO {



    // إضافة منتج
    public void insert(Product product){


        String sql = """

        INSERT INTO Products
        (
        code,
        company_id,
        category_id,
        name,
        size,
        unit_type,
        default_wholesale_price,
        default_selling_price,
        notes
        )

        VALUES(?,?,?,?,?,?,?,?,?)

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1, product.getCode());

            stmt.setInt(2, product.getCompanyId());

            stmt.setInt(3, product.getCategoryId());

            stmt.setString(4, product.getName());

            stmt.setString(5, product.getSize());

            stmt.setString(6, product.getUnitType());

            stmt.setDouble(7, product.getDefaultWholesalePrice());

            stmt.setDouble(8, product.getDefaultSellingPrice());

            stmt.setString(9, product.getNotes());


            stmt.executeUpdate();


        }
        catch(SQLException e){
            throw new DatabaseException("خطأ في إلغاء تفعيل المنتج", e);
        }


    }





    // جلب كل المنتجات
    public List<Product> findAll(){


        List<Product> products = new ArrayList<>();


        String sql = """

        SELECT *
        FROM Products
        WHERE is_active = 1
        ORDER BY name

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(sql)
        ){


            while(rs.next()){


                products.add(mapProduct(rs));


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب المنتجات", e);
        }

        return products;

    }



    // البحث الذكي
    public List<Product> search(String text){


        List<Product> products = new ArrayList<>();


        String sql = """

        SELECT *
        FROM Products
        WHERE is_active = 1

        AND
        (
            code LIKE ?
            OR name LIKE ?
            OR size LIKE ?
        )

        ORDER BY name

        """;



        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            String value = "%" + text + "%";


            stmt.setString(1,value);
            stmt.setString(2,value);
            stmt.setString(3,value);



            ResultSet rs = stmt.executeQuery();



            while(rs.next()){


                products.add(mapProduct(rs));


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في البحث عن المنتجات", e);
        }

        return products;

    }



    // البحث بالكود
    public Product findByCode(String code){


        String sql = """

        SELECT *
        FROM Products
        WHERE code = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setString(1,code);


            ResultSet rs = stmt.executeQuery();



            if(rs.next()){

                return mapProduct(rs);

            }


        }        catch(SQLException e){
            throw new DatabaseException("خطأ في البحث بالكود", e);
        }

        return null;

    }



    // جلب منتج بالرقم
    public Product findById(int id){


        String sql = """

        SELECT *
        FROM Products
        WHERE id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setInt(1,id);


            ResultSet rs = stmt.executeQuery();



            if(rs.next()){

                return mapProduct(rs);

            }


        }        catch(SQLException e){
            throw new DatabaseException("خطأ في جلب المنتج بالرقم", e);
        }

        return null;

    }



    // تعديل بيانات المنتج
    public void update(Product product){


        String sql = """

        UPDATE Products

        SET
        company_id=?,
        category_id=?,
        name=?,
        size=?,
        unit_type=?,
        notes=?

        WHERE id=?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setInt(1,product.getCompanyId());

            stmt.setInt(2,product.getCategoryId());

            stmt.setString(3,product.getName());

            stmt.setString(4,product.getSize());

            stmt.setString(5,product.getUnitType());

            stmt.setString(6,product.getNotes());

            stmt.setInt(7,product.getId());


            stmt.executeUpdate();


        }        catch(SQLException e){
            throw new DatabaseException("خطأ في تحديث المنتج", e);
        }

    }



    // إلغاء تفعيل المنتج
    public void deactivate(int id){


        String sql = """

        UPDATE Products

        SET is_active = 0

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
            throw new DatabaseException("خطأ في إلغاء تفعيل المنتج", e);
        }


    }





    // تحويل ResultSet إلى Product
    private Product mapProduct(ResultSet rs) throws SQLException{


        Product product = new Product();


        product.setId(
                rs.getInt("id")
        );


        product.setCode(
                rs.getString("code")
        );


        product.setCompanyId(
                rs.getInt("company_id")
        );


        product.setCategoryId(
                rs.getInt("category_id")
        );


        product.setName(
                rs.getString("name")
        );


        product.setSize(
                rs.getString("size")
        );


        product.setUnitType(
                rs.getString("unit_type")
        );


        product.setDefaultWholesalePrice(
                rs.getDouble("default_wholesale_price")
        );


        product.setDefaultSellingPrice(
                rs.getDouble("default_selling_price")
        );


        product.setNotes(
                rs.getString("notes")
        );


        product.setActive(
                rs.getBoolean("is_active")
        );


        return product;


    }



}