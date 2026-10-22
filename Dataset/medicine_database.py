import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

db = pd.read_csv("medex_medicine.csv")
db2 = pd.read_csv("SquarePharma_medicine.csv")

print(db.head)
print(db.columns)
print(db2.columns)

print(db.size)
print(db.shape)
print(db2.size)
print(db2.shape)

print(db.loc[: ,"Name"])

print(db2.loc[:, "Name"])
print("\n\n")
db3 = pd.concat([db["Name"] , db2["Name"]], ignore_index = True)

print(db3.shape)

db3.to_json("medicines.json", orient="records")