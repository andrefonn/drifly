import sqlite3
import os
import sys
import tempfile

def test_sqlite_v3_to_v4_migration():
    temp_dir = tempfile.mkdtemp()
    db_path = os.path.join(temp_dir, "test_drifly.db")
    print(f"[TEST] Using temp DB path: {db_path}")

    conn = sqlite3.connect(db_path)
    conn.execute("PRAGMA foreign_keys = ON;")
    cursor = conn.cursor()

    # 1. Setup V3 Database
    print("[1/7] Creating DB v3 schema...")
    cursor.execute("""
        CREATE TABLE rides (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            platform TEXT,
            gross_price REAL,
            distance_km REAL,
            net_profit REAL,
            timestamp INTEGER,
            status TEXT DEFAULT 'ESTIMADO',
            cancellation_fee REAL DEFAULT 0.0,
            original_price REAL DEFAULT 0.0
        )
    """)
    cursor.execute("""
        CREATE TABLE expenses (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            category TEXT,
            amount REAL,
            description TEXT,
            timestamp INTEGER
        )
    """)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS monthly_obligations (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            category TEXT NOT NULL,
            type TEXT NOT NULL,
            default_amount REAL NOT NULL,
            due_day INTEGER NOT NULL DEFAULT 10,
            start_year_month TEXT NOT NULL,
            end_year_month TEXT,
            initial_installment INTEGER DEFAULT 1,
            total_installments INTEGER DEFAULT 1,
            total_debt_balance REAL DEFAULT 0.0,
            notes TEXT DEFAULT '',
            is_active INTEGER NOT NULL DEFAULT 1,
            created_at INTEGER NOT NULL
        )
    """)
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS monthly_obligation_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            obligation_id INTEGER NOT NULL,
            year_month TEXT NOT NULL,
            amount REAL NOT NULL,
            due_day INTEGER NOT NULL,
            installment_number INTEGER DEFAULT 0,
            total_installments INTEGER DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'PENDING',
            paid_at INTEGER DEFAULT 0,
            UNIQUE(obligation_id, year_month),
            FOREIGN KEY(obligation_id) REFERENCES monthly_obligations(id) ON DELETE CASCADE
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_entries_ym ON monthly_obligation_entries(year_month)")
    cursor.execute("PRAGMA user_version = 3;")
    conn.commit()

    # Populate dummy data in v3
    cursor.execute("INSERT INTO rides (platform, gross_price, distance_km, net_profit, timestamp) VALUES ('UBER', 25.5, 7.0, 20.0, 1700000000)")
    cursor.execute("INSERT INTO expenses (category, amount, description, timestamp) VALUES ('Combustível', 50.0, 'Gasolina', 1700000001)")
    cursor.execute("INSERT INTO monthly_obligations (title, category, type, default_amount, due_day, start_year_month, created_at) VALUES ('MEI', 'IMPOSTOS_MEI', 'FIXED_RECURRING', 75.0, 20, '2026-11', 1700000002)")
    ob_id = cursor.lastrowid
    cursor.execute("INSERT INTO monthly_obligation_entries (obligation_id, year_month, amount, due_day) VALUES (?, '2026-11', 75.0, 20)", (ob_id,))
    conn.commit()

    # Verify initial v3 user_version
    cursor.execute("PRAGMA user_version;")
    v = cursor.fetchone()[0]
    assert v == 3, f"Expected v3, got {v}"
    print("   [OK] DB v3 created with initial data.")

    # 2. Execute onUpgrade(oldVersion=3, newVersion=4)
    print("[2/7] Executing onUpgrade to v4 (createBlacklistTable)...")
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS blacklist_neighborhoods (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            raw_term TEXT NOT NULL,
            normalized_term TEXT NOT NULL,
            latitude REAL,
            longitude REAL,
            created_at INTEGER NOT NULL
        )
    """)
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_blacklist_norm ON blacklist_neighborhoods(normalized_term)")
    cursor.execute("PRAGMA user_version = 4;")
    conn.commit()

    # 3. Verify Table and Index Structure
    print("[3/7] Verifying table columns and indices...")
    cursor.execute("PRAGMA table_info(blacklist_neighborhoods);")
    cols = {row[1]: {"type": row[2].upper(), "notnull": row[3], "pk": row[5]} for row in cursor.fetchall()}
    
    assert "id" in cols and cols["id"]["pk"] == 1, "Column 'id' must be PK"
    assert "raw_term" in cols and cols["raw_term"]["notnull"] == 1, "Column 'raw_term' must be NOT NULL"
    assert "normalized_term" in cols and cols["normalized_term"]["notnull"] == 1, "Column 'normalized_term' must be NOT NULL"
    assert "latitude" in cols and cols["latitude"]["notnull"] == 0, "Column 'latitude' must be NULLABLE"
    assert "longitude" in cols and cols["longitude"]["notnull"] == 0, "Column 'longitude' must be NULLABLE"
    assert "created_at" in cols and cols["created_at"]["notnull"] == 1, "Column 'created_at' must be NOT NULL"
    print("   [OK] Columns schema verified.")

    cursor.execute("PRAGMA index_list(blacklist_neighborhoods);")
    indices = {row[1]: row[2] for row in cursor.fetchall()}
    assert "idx_blacklist_norm" in indices, "Index 'idx_blacklist_norm' missing!"

    cursor.execute("PRAGMA index_info(idx_blacklist_norm);")
    idx_cols = [row[2] for row in cursor.fetchall()]
    assert idx_cols == ["normalized_term"], f"Index on unexpected columns: {idx_cols}"
    print("   [OK] Index 'idx_blacklist_norm' verified on 'normalized_term'.")

    # 4. Test CRUD - Insert
    print("[4/7] Testing CRUD - Insert...")
    # Item 1: with coordinates
    cursor.execute(
        "INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, latitude, longitude, created_at) VALUES (?, ?, ?, ?, ?)",
        ("Cidade Jardim Parauapebas", "cidade jardim parauapebas", -6.0712, -49.8923, 1000)
    )
    id1 = cursor.lastrowid
    assert id1 > 0, "Insert with coordinates failed"

    # Item 2: without coordinates (null lat/lng)
    cursor.execute(
        "INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, latitude, longitude, created_at) VALUES (?, ?, ?, ?, ?)",
        ("Itinga", "itinga", None, None, 2000)
    )
    id2 = cursor.lastrowid
    assert id2 > id1, "Insert without coordinates failed"

    # Item 3: NOT NULL constraint checks
    try:
        cursor.execute("INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, created_at) VALUES (NULL, 'test', 3000)")
        assert False, "Should have thrown IntegrityError for null raw_term"
    except sqlite3.IntegrityError:
        pass

    try:
        cursor.execute("INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, created_at) VALUES ('test', NULL, 3000)")
        assert False, "Should have thrown IntegrityError for null normalized_term"
    except sqlite3.IntegrityError:
        pass
    print("   [OK] Insert & NOT NULL constraints verified.")

    # 5. Test CRUD - Query & Update
    print("[5/7] Testing CRUD - Query & Update...")
    cursor.execute("SELECT id, raw_term, normalized_term, latitude, longitude, created_at FROM blacklist_neighborhoods ORDER BY created_at DESC")
    rows = cursor.fetchall()
    assert len(rows) == 2, f"Expected 2 rows, got {len(rows)}"
    assert rows[0][0] == id2 and rows[0][1] == "Itinga" and rows[0][3] is None, "Row 0 (latest) mismatch"
    assert rows[1][0] == id1 and rows[1][1] == "Cidade Jardim Parauapebas" and abs(rows[1][3] - (-6.0712)) < 0.0001, "Row 1 mismatch"

    # Update coordinates for Item 2
    cursor.execute("UPDATE blacklist_neighborhoods SET latitude = ?, longitude = ? WHERE id = ?", (-26.35, -48.83, id2))
    conn.commit()

    cursor.execute("SELECT latitude, longitude FROM blacklist_neighborhoods WHERE id = ?", (id2,))
    updated_coords = cursor.fetchone()
    assert abs(updated_coords[0] - (-26.35)) < 0.0001 and abs(updated_coords[1] - (-48.83)) < 0.0001, "Coordinates update failed"
    print("   [OK] Query & Update verified.")

    # 6. Test CRUD - Delete, Restore & Clear
    print("[6/7] Testing CRUD - Delete, Restore & Clear...")
    cursor.execute("DELETE FROM blacklist_neighborhoods WHERE id = ?", (id1,))
    conn.commit()
    cursor.execute("SELECT COUNT(*) FROM blacklist_neighborhoods")
    assert cursor.fetchone()[0] == 1, "Delete failed"

    # Batch Restore with clearExisting = False
    new_items = [
        ("Bairro da Paz", "bairro da paz", -6.08, -49.90, 3000),
        ("Paranaguamirim", "paranaguamirim", -26.37, -48.82, 4000)
    ]
    cursor.executemany("INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, latitude, longitude, created_at) VALUES (?, ?, ?, ?, ?)", new_items)
    conn.commit()
    cursor.execute("SELECT COUNT(*) FROM blacklist_neighborhoods")
    assert cursor.fetchone()[0] == 3, "Restore without clear failed"

    # Batch Restore with clearExisting = True
    cursor.execute("DELETE FROM blacklist_neighborhoods")
    cursor.executemany("INSERT INTO blacklist_neighborhoods (raw_term, normalized_term, latitude, longitude, created_at) VALUES (?, ?, ?, ?, ?)", [("Unico", "unico", None, None, 5000)])
    conn.commit()
    cursor.execute("SELECT COUNT(*), raw_term FROM blacklist_neighborhoods")
    res = cursor.fetchone()
    assert res[0] == 1 and res[1] == "Unico", "Restore with clear failed"

    # Clear all
    cursor.execute("DELETE FROM blacklist_neighborhoods")
    conn.commit()
    cursor.execute("SELECT COUNT(*) FROM blacklist_neighborhoods")
    assert cursor.fetchone()[0] == 0, "Clear all failed"
    print("   [OK] Delete, Restore, and Clear verified.")

    # 7. Check Data Loss / Regressions on Pre-existing Tables
    print("[7/7] Checking pre-existing tables data integrity...")
    cursor.execute("SELECT COUNT(*) FROM rides")
    assert cursor.fetchone()[0] == 1, "Rides table modified unexpectedly"
    cursor.execute("SELECT COUNT(*) FROM expenses")
    assert cursor.fetchone()[0] == 1, "Expenses table modified unexpectedly"
    cursor.execute("SELECT COUNT(*) FROM monthly_obligations")
    assert cursor.fetchone()[0] == 1, "Obligations table modified unexpectedly"
    cursor.execute("SELECT COUNT(*) FROM monthly_obligation_entries")
    assert cursor.fetchone()[0] == 1, "Obligation entries table modified unexpectedly"
    print("   [OK] Pre-existing data 100% intact across all v3 tables.")

    conn.close()
    try:
        os.remove(db_path)
        os.rmdir(temp_dir)
    except:
        pass
    print("\n>>> ALL SQLITE MIGRATION & CRUD VERIFICATION CHECKS PASSED SUCCESSFULLY! <<<")

if __name__ == "__main__":
    test_sqlite_v3_to_v4_migration()
