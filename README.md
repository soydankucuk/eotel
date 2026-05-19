# E-Otel Rezervasyon Yonetim Sistemi

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java)
![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)

Veri yapilari odakli, saf Java SE ile yazilmis masaustu otel rezervasyon sistemi.  
**GUI (Swing)** veya **terminal menusu** ile calisir. Hicbir dis kutuphane kullanilmamistir.

--- 

## Kullanilan Veri Yapilari

| Yapi | Kullanim | Karmasiklik |
|---|---|---|
| **BST** (sifirdan) | Gecmis rezervasyonlari tarihe gore sirali tutar | O(log n) insert, O(n) gezinme |
| **Interval Kontrolu** | Tarih araligi carpismasi kontrolu | O(n) |
| **HashMap** | Oda ve musteriye ID ile aninda erisim | O(1) |
| **LinkedList** | Bekleme listesi — FIFO kuyrugu | O(1) ekleme/cikarma |

---

## Gereksinimler

- **JDK 17+** — [https://adoptium.net](https://adoptium.net)

---

## Kurulum ve Calistirma

```bash
# Derle
javac HotelManagementSystem.java

# GUI ile calistir
java -cp . HotelManagementSystem

# Terminal menusu ile calistir
java -cp . HotelManagementSystem terminal
```

> **Windows PowerShell:**
> ```powershell
> cd "C:\Users\...\eotel"
> & "C:\Program Files\Java\jdk-17\bin\javac.exe" HotelManagementSystem.java
> & "C:\Program Files\Java\jdk-17\bin\java.exe" -cp . HotelManagementSystem
> & "C:\Program Files\Java\jdk-17\bin\java.exe" -cp . HotelManagementSystem terminal
> ```

---

## Ozellikler

- Rezervasyon olusturma ve musaitlik kontrolu
- Dolu oda icin otomatik bekleme listesi
- Aktif rezervasyonlari goruntuleme ve iptal etme
- Gecmis kayitlar (tarihe gore sirali — BST)
- Coklu sube destegi (Sube A, B, C)
- Otomatik veri kaydi (`hotel_data.csv`)

---

## Proje Yapisi

```
e-otel/
├── HotelManagementSystem.java   # Tek kaynak dosya
├── hotel_data.csv               # Otomatik olusturulan kayit dosyasi
└── README.md
```

---

## Lisans

MIT License — dilediginiz gibi kullanabilirsiniz.
