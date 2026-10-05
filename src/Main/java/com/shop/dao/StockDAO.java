package com.shop.dao;


import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.Stock;

import java.sql.*;



public class StockDAO {



    // إنشاء رصيد للمنتج أول مرة
    public void createStock(int productId){


        String sql = """

        INSERT OR IGNORE INTO Stock
        (
        product_id,
        total_quantity
        )

        VALUES(?,0)

        """;



        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setInt(1, productId);

            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في إنشاء رصيد المنتج", e);
        }

    }



    // زيادة الرصيد
    public void increaseQuantity(
            int productId,
            double quantity
    ){


        String sql = """

        UPDATE Stock

        SET total_quantity = total_quantity + ?

        WHERE product_id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setDouble(1, quantity);

            stmt.setInt(2, productId);


            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في زيادة رصيد المنتج", e);
        }

    }



    // تقليل الرصيد
    public void decreaseQuantity(
            int productId,
            double quantity
    ){


        String sql = """

        UPDATE Stock

        SET total_quantity = total_quantity - ?

        WHERE product_id = ?

        AND total_quantity >= ?

        """;



        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setDouble(1,quantity);

            stmt.setInt(2,productId);

            stmt.setDouble(3,quantity);


            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في خفض رصيد المنتج", e);
        }

    }



    // جلب رصيد منتج
    public double getQuantity(int productId){


        String sql = """

        SELECT total_quantity

        FROM Stock

        WHERE product_id = ?

        """;



        try(
            Connection conn = DatabaseManager.getConnection();

            PreparedStatement stmt = conn.prepareStatement(sql)

        ){


            stmt.setInt(1,productId);


            ResultSet rs = stmt.executeQuery();



            if(rs.next()){


                return rs.getDouble("total_quantity");


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب رصيد المنتج", e);
        }

        return 0;

    }



    // جلب سجل Stock كامل لمنتج
    public Stock findByProduct(int productId){


        String sql = """

        SELECT *

        FROM Stock

        WHERE product_id = ?

        """;


        try(
            Connection conn = DatabaseManager.getConnection();

            PreparedStatement stmt = conn.prepareStatement(sql)

        ){


            stmt.setInt(1,productId);


            ResultSet rs = stmt.executeQuery();



            if(rs.next()){


                Stock stock = new Stock();


                stock.setId(
                        rs.getInt("id")
                );


                stock.setProductId(
                        rs.getInt("product_id")
                );


                stock.setTotalQuantity(
                        rs.getDouble("total_quantity")
                );


                return stock;


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب سجل المخزون", e);
        }

        return null;

    }

}