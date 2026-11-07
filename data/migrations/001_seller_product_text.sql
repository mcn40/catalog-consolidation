PRAGMA foreign_keys = OFF;

BEGIN TRANSACTION;

CREATE TABLE SellerProduct_new (
    Id INTEGER PRIMARY KEY AUTOINCREMENT,
    SellerName TEXT NOT NULL,
    ProductId INTEGER NOT NULL,
    SellerProductId TEXT NOT NULL,
    CONSTRAINT FK_Product_Id FOREIGN KEY (ProductId) REFERENCES Product (Id),
    CONSTRAINT UQ_SellerProduct_Seller_SellerProductId
        UNIQUE (SellerName, SellerProductId)
);

INSERT INTO SellerProduct_new (Id, SellerName, ProductId, SellerProductId)
SELECT Id, SellerName, ProductId, CAST(SellerProductId AS TEXT)
FROM SellerProduct;

DROP TABLE SellerProduct;
ALTER TABLE SellerProduct_new RENAME TO SellerProduct;

COMMIT;

PRAGMA foreign_keys = ON;
