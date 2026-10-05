package com.shop.dao;


import com.shop.database.DatabaseManager;
import com.shop.exception.DatabaseException;
import com.shop.model.Batch;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;



public class BatchDAO {



    // إضافة دفعة جديدة
    public void insert(Batch batch){


        String sql = """

        INSERT INTO Batches
        (
        product_id,
        quantity_remaining,
        wholesale_price,
        date_in
        )

        VALUES(?,?,?,?)

        """;



        try(
            Connection conn = DatabaseManager.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ){


            stmt.setInt(
                    1,
                    batch.getProductId()
            );


            stmt.setDouble(
                    2,
                    batch.getQuantityRemaining()
            );


            stmt.setDouble(
                    3,
                    batch.getWholesalePrice()
            );


            stmt.setString(
                    4,
                    batch.getDateIn()
            );


            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في إضافة دفعة", e);
        }

    }



    // جلب الدفعات المتاحة حسب FIFO
    public List<Batch> findAvailableByProduct(int productId){


        List<Batch> batches = new ArrayList<>();


        String sql = """

        SELECT *

        FROM Batches

        WHERE product_id = ?

        AND quantity_remaining > 0

        ORDER BY date_in ASC

        """;



        try(
            Connection conn = DatabaseManager.getConnection();

            PreparedStatement stmt = conn.prepareStatement(sql)

        ){


            stmt.setInt(1,productId);



            ResultSet rs = stmt.executeQuery();



            while(rs.next()){


                Batch batch = new Batch();


                batch.setId(
                        rs.getInt("id")
                );


                batch.setProductId(
                        rs.getInt("product_id")
                );


                batch.setQuantityRemaining(
                        rs.getDouble("quantity_remaining")
                );


                batch.setWholesalePrice(
                        rs.getDouble("wholesale_price")
                );


                batch.setDateIn(
                        rs.getString("date_in")
                );



                batches.add(batch);


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب الدفعات", e);
        }

        return batches;

    }



    // تحديث كمية الدفعة
    public void updateQuantity(
            int batchId,
            double quantity
    ){


        String sql = """

        UPDATE Batches

        SET quantity_remaining = ?

        WHERE id = ?

        """;



        try(
            Connection conn = DatabaseManager.getConnection();

            PreparedStatement stmt = conn.prepareStatement(sql)

        ){


            stmt.setDouble(1,quantity);

            stmt.setInt(2,batchId);


            stmt.executeUpdate();


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في تحديث كمية الدفعة", e);
        }

    }



    // معرفة كمية المخزون في كل الدفعات
    public double getTotalQuantity(int productId){


        String sql = """

        SELECT SUM(quantity_remaining)

        FROM Batches

        WHERE product_id = ?

        """;



        try(
            Connection conn = DatabaseManager.getConnection();

            PreparedStatement stmt = conn.prepareStatement(sql)

        ){


            stmt.setInt(1,productId);


            ResultSet rs = stmt.executeQuery();



            if(rs.next()){


                return rs.getDouble(1);


            }


        }
        catch(SQLException e){            throw new DatabaseException("خطأ في جلب إجمالي كمية المخزون", e);
        }

        return 0;

    }

}