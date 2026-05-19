import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * E-Otel Rezervasyon Yonetim Sistemi
 *
 * Kullanilan Veri Yapilari:
 *   - Ozel BST (Binary Search Tree) : Gecmis rezervasyonlari giris tarihine gore sirali tutar.
 *   - Aralik (Interval) Carpismasi   : Oda mevcut mu diye kontrol ederken tarih araliklar cakisiyor mu bakar.
 *   - HashMap                        : Oda ve musteri bilgilerine O(1) surede erismek icin kullanilir.
 *   - LinkedList                     : Bekleme listesini kuyruk mantiginda yonetir.
 */
public class HotelManagementSystem {

    // -------------------------------------------------------
    // VERI MODELLERI — Sistemdeki temel nesneleri tanimlayan siniflar
    // -------------------------------------------------------

    static class Room {
        final String id, number, type, branch;
        // Her rezervasyon icin [giris_gun, cikis_gun] ciftlerini epoch-day formatinda saklar.
        // Epoch-day: 1 Ocak 1970'den itibaren kac gun gectigi anlamina gelir; tarih karsilastirmayi kolaylastirir.
        final List<long[]> intervals = new ArrayList<>();

        Room(String id, String number, String type, String branch) {
            this.id = id; this.number = number; this.type = type; this.branch = branch;
        }

        /**
         * Aralik Carpisma Kontrolu — Simplified Interval Tree mantigi
         *
         * Iki tarih araligi [a,b) ve [c,d) CARPISIYOR mu sorusuna cevap verir.
         * Carpisma kurali: a < d VE c < b ise iki aralik ortusiyor demektir.
         * Ornek: [3,7) ve [5,9) -> 3<9 ve 5<7 -> CARPISIYOR (musait degil)
         *        [3,5) ve [6,9) -> 3<9 ama 6<5 degil -> CARPISMYOR (musait)
         *
         * Zaman: O(n) — odanin mevcut rezervasyon sayisi kadar dongu doner
         * Alan:  O(1) — ekstra bellek kullanilmaz
         */
        boolean isAvailable(LocalDate in, LocalDate out) {
            long a = in.toEpochDay(), b = out.toEpochDay();
            for (long[] iv : intervals) if (a < iv[1] && iv[0] < b) return false;
            return true;
        }

        void book(LocalDate in, LocalDate out) {
            intervals.add(new long[]{in.toEpochDay(), out.toEpochDay()});
        }

        void release(LocalDate in, LocalDate out) {
            intervals.removeIf(iv -> iv[0] == in.toEpochDay() && iv[1] == out.toEpochDay());
        }
    }

    static class Customer {
        final String id, name, email;
        Customer(String id, String name, String email) {
            this.id = id; this.name = name; this.email = email;
        }
    }

    static class Reservation {
        final String id, roomId, custId;
        final LocalDate in, out;
        String status; // AKTIF=ACTIVE | BEKLEME=WAITLIST | TAMAMLANDI=COMPLETED

        Reservation(String id, String rId, String cId, LocalDate in, LocalDate out, String status) {
            this.id = id; this.roomId = rId; this.custId = cId;
            this.in = in; this.out = out; this.status = status;
        }
    }

    // -------------------------------------------------------
    // OZEL BST (Ikili Arama Agaci) — Java'nin TreeSet/TreeMap sinifi KULLANILMADI,
    // sifirdan elle yazildi. Tamamlanan rezervasyonlari giris tarihine gore sirali saklar.
    // -------------------------------------------------------

    static class BST {
        // Agacin her dugumu bir rezervasyon ve sol/sag cocuk referansi tasir.
        private static class Node {
            Reservation d; Node l, r;
            Node(Reservation d) { this.d = d; }
        }
        private Node root; // Agacin kok dugumu

        /**
         * Yeni rezervasyonu agaca ekle (giris tarihine gore siralama).
         * Kural: yeni tarih mevcut dugumden kucukse SOLA, buyuk/esitse SAGA git.
         *
         * Zaman: O(log n) ortalama (dengeli agac), O(n) en kotu (egreti agac)
         * Alan:  O(log n) — rekaursif cagri yigini kadar yer kullanir
         */
        void insert(Reservation r) { root = ins(root, r); }

        private Node ins(Node n, Reservation r) {
            if (n == null) return new Node(r);   // Bos yer bulundu, dugumu buraya yerlestir
            if (r.in.isBefore(n.d.in)) n.l = ins(n.l, r);  // Daha eski tarih -> sol dala git
            else                        n.r = ins(n.r, r);  // Daha yeni tarih -> sag dala git
            return n;
        }

        /**
         * Inorder (Sol-Kok-Sag) gezinme — sonuclari giris tarihine gore kucukten buyuge siralar.
         * BST ozelliginden dolayi inorder gezinme her zaman sirali liste uretir.
         *
         * Zaman: O(n) — her dugum tam olarak bir kez ziyaret edilir
         * Alan:  O(n) donus listesi + O(h) rekaursif yigin (h = agac yuksekligi)
         */
        List<Reservation> inorder() {
            List<Reservation> out = new ArrayList<>();
            inorder(root, out);
            return out;
        }

        private void inorder(Node n, List<Reservation> out) {
            if (n == null) return;
            inorder(n.l, out); out.add(n.d); inorder(n.r, out); // once sol, sonra kok, sonra sag
        }
    }

    // -------------------------------------------------------
    // VERI DEPOLARI — Her veri yapisi farkli bir amac icin secildi
    //
    //   HashMap   -> Anahtar-deger eslemesi. ID ile odaya/musteriye O(1) surede erisim.
    //                Ornekle: rooms.get("R1") direkt odayi getirir, liste aramak gerekmez.
    //
    //   LinkedList -> Bagli liste / kuyruk yapisi. Bekleme listesine O(1) ile ekleme/cikarma.
    //                 Ilk gelen ilk cikar (FIFO) mantigiyla calisir.
    //
    //   BST        -> Tamamlanan rezervasyonlari giris tarihine gore sirali tutar.
    //                 Gecmis sekmesinde veriler her zaman tarihe gore siralanmis gorunur.
    //
    //   ArrayList  -> Aktif rezervasyonlar icin basit liste; kucuk veri setinde yeterlidir.
    // -------------------------------------------------------

    static final HashMap<String, Room>     rooms    = new HashMap<>();  // Oda ID -> Oda nesnesi
    static final HashMap<String, Customer> custs    = new HashMap<>();  // Musteri ID -> Musteri nesnesi
    static final LinkedList<Reservation>   waitlist = new LinkedList<>(); // Bekleme sirasi (FIFO)
    static final BST                       history  = new BST();          // Tamamlanan rezervasyonlar (BST)
    static final List<Reservation>         active   = new ArrayList<>();  // Suanda aktif rezervasyonlar

    static final DateTimeFormatter FMT  = DateTimeFormatter.ofPattern("dd-MM-yyyy"); // Tarih formati: gun-ay-yil
    static final String            FILE = "hotel_data.csv"; // Kayit dosyasinin adi

    static DefaultTableModel roomModel, waitModel, histModel, activeModel;

    // -------------------------------------------------------
    // PROGRAM BASLANGICI
    // -------------------------------------------------------

    public static void main(String[] args) {
        loadData();
        Runtime.getRuntime().addShutdownHook(new Thread(HotelManagementSystem::saveData));
        if (args.length > 0 && args[0].equalsIgnoreCase("terminal")) {
            terminalMenu(); // Konsol modu: java ... HotelManagementSystem terminal
        } else {
            SwingUtilities.invokeLater(HotelManagementSystem::buildUI); // GUI modu (varsayilan)
        }
    }

    // -------------------------------------------------------
    // ARAYUZ — Swing ile olusturulan pencere ve sekmeler
    // -------------------------------------------------------

    static void buildUI() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        JFrame frame = new JFrame("E-Otel Rezervasyon Sistemi");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(960, 620);
        frame.setLocationRelativeTo(null);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(22, 55, 100));
        header.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 20));
        JLabel title = new JLabel("E-OTEL REZERVASYON SISTEMI", SwingConstants.CENTER);
        title.setFont(new Font("Arial", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        header.add(title, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Arial", Font.BOLD, 13));
        tabs.addTab("  Yeni Rezervasyon  ",     buildReservationTab());
        tabs.addTab("  Aktif Rezervasyonlar  ", buildActiveTab());
        tabs.addTab("  Odalar  ",               buildRoomsTab());
        tabs.addTab("  Bekleme Listesi  ",      buildWaitlistTab());
        tabs.addTab("  Gecmis  ",               buildHistoryTab());

        frame.add(header, BorderLayout.NORTH);
        frame.add(tabs,   BorderLayout.CENTER);
        frame.setVisible(true);
    }

    static JPanel buildReservationTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(20, 80, 20, 80));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(7, 7, 7, 7);
        c.fill   = GridBagConstraints.HORIZONTAL;

        String[]     labels = {"Musteri Adi:", "Musteri E-posta:", "Oda Numarasi:",
                               "Giris Tarihi (gg-AA-yyyy):", "Cikis Tarihi (gg-AA-yyyy):", "Sube (opsiyonel):"};
        JTextField[] fields = new JTextField[labels.length];

        for (int i = 0; i < labels.length; i++) {
            c.gridx = 0; c.gridy = i; panel.add(new JLabel(labels[i]), c);
            c.gridx = 1; fields[i] = new JTextField(24); panel.add(fields[i], c);
        }

        JButton bookBtn = new JButton("Rezervasyon Yap");
        bookBtn.setBackground(new Color(41, 128, 185));
        bookBtn.setForeground(Color.BLACK);
        bookBtn.setFocusPainted(false);
        bookBtn.setFont(new Font("Arial", Font.BOLD, 13));
        c.gridx = 0; c.gridy = labels.length; c.gridwidth = 2;
        panel.add(bookBtn, c);

        JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
        c.gridy = labels.length + 1;
        panel.add(statusLabel, c);

        bookBtn.addActionListener(e -> handleBooking(fields, statusLabel));
        return panel;
    }

    static void handleBooking(JTextField[] f, JLabel statusLabel) {
        String name    = f[0].getText().trim(), email  = f[1].getText().trim(),
               roomNum = f[2].getText().trim(), ciStr  = f[3].getText().trim(),
               coStr   = f[4].getText().trim(), branch = f[5].getText().trim();

        if (Stream.of(name, email, roomNum, ciStr, coStr).anyMatch(String::isEmpty)) {
            warn("Sube haric tum alanlar zorunludur."); return;
        }

        LocalDate ci, co;
        try { ci = LocalDate.parse(ciStr, FMT); co = LocalDate.parse(coStr, FMT); }
        catch (Exception ex) { warn("Gecersiz tarih formati. gg-AA-yyyy kullanin. Ornek: 25-06-2026"); return; }
        if (!co.isAfter(ci)) { warn("Cikis tarihi giris tarihinden sonra olmalidir."); return; }

        final String b = branch.isEmpty() ? null : branch;
        Room room = rooms.values().stream()
                .filter(r -> r.number.equals(roomNum) && (b == null || r.branch.equalsIgnoreCase(b)))
                .findFirst().orElse(null);
        if (room == null) { warn("Oda bulunamadi. Oda numarasi / subeyi kontrol edin."); return; }

        String cid = "C" + System.currentTimeMillis();
        custs.put(cid, new Customer(cid, name, email));

        String rid = "R" + System.currentTimeMillis();
        Reservation res = new Reservation(rid, room.id, cid, ci, co, "");

        if (room.isAvailable(ci, co)) {
            room.book(ci, co);
            res.status = "ACTIVE";
            active.add(res);
            statusLabel.setText("Rezervasyon olusturuldu: " + rid);
            info("Rezervasyon onaylandi!\nID: " + rid);
        } else {
            res.status = "WAITLIST";
            waitlist.add(res);
            statusLabel.setText("Bekleme listesine eklendi: " + rid);
            warn("Oda bu tarihler icin musait degil.\nBekleme listesine eklendi - ID: " + rid);
        }
        refreshAll();
    }

    static JPanel buildActiveTab() {
        activeModel = model("Rezervasyon ID", "Musteri", "E-posta", "Oda No", "Sube", "Giris", "Cikis");
        JTable table = styledTable(activeModel);

        JButton cancelBtn = new JButton("Secili Rezervasyonu Iptal Et");
        cancelBtn.setBackground(new Color(192, 57, 43));
        cancelBtn.setForeground(Color.BLACK);
        cancelBtn.setFocusPainted(false);
        cancelBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { warn("Once bir rezervasyon secin."); return; }
            String resId = (String) activeModel.getValueAt(row, 0);
            doCancelById(resId);
        });

        refreshActive();
        return borderPanel(new JScrollPane(table), cancelBtn);
    }

    static JPanel buildRoomsTab() {
        roomModel = model("Oda No", "Tip", "Sube", "Durum");
        JTable table = styledTable(roomModel);

        // Sag tik menusu: aktif rezervasyonu iptal et
        JPopupMenu popup = new JPopupMenu();
        JMenuItem cancelItem = new JMenuItem("Rezervasyonu Iptal Et");
        cancelItem.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { warn("Once bir oda satiri secin."); return; }
            doCancel((String) roomModel.getValueAt(row, 0));
        });
        popup.add(cancelItem);
        table.setComponentPopupMenu(popup);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) table.setRowSelectionInterval(row, row);
            }
        });

        JButton checkoutBtn = new JButton("Secili Odadan Cikis Yap");
        checkoutBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { warn("Once bir oda satiri secin."); return; }
            doCheckout((String) roomModel.getValueAt(row, 0));
        });

        refreshRooms();
        return borderPanel(new JScrollPane(table), checkoutBtn);
    }

    static JPanel buildWaitlistTab() {
        waitModel = model("Rezervasyon ID", "Musteri", "Oda No", "Giris", "Cikis");
        JTable table = styledTable(waitModel);
        refreshWaitlist();
        return borderPanel(new JScrollPane(table), refreshBtn(e -> refreshWaitlist()));
    }

    static JPanel buildHistoryTab() {
        histModel = model("Rezervasyon ID", "Musteri", "Oda No", "Giris", "Cikis", "Durum");
        JTable table = styledTable(histModel);
        refreshHistory();
        return borderPanel(new JScrollPane(table), refreshBtn(e -> refreshHistory()));
    }

    // -------------------------------------------------------
    // IPTAL & CIKIS — Rezervasyon iptali ve oda bosaltma islemleri
    // -------------------------------------------------------

    // Oda numarasina gore aktif rezervasyonu iptal eder (sag tik menusu icin)
    static void doCancel(String roomNum) {
        Reservation res = active.stream()
            .filter(r -> { Room rm = rooms.get(r.roomId); return rm != null && rm.number.equals(roomNum); })
            .findFirst().orElse(null);
        if (res == null) { warn("Oda " + roomNum + " icin iptal edilecek aktif rezervasyon yok."); return; }
        doCancelById(res.id);
    }

    // Rezervasyon ID'sine gore iptal eder (Aktif Rezervasyonlar sekmesi icin)
    static void doCancelById(String resId) {
        Reservation res = active.stream().filter(r -> r.id.equals(resId)).findFirst().orElse(null);
        if (res == null) { warn("Rezervasyon bulunamadi."); return; }
        Customer c = custs.get(res.custId);
        Room room  = rooms.get(res.roomId);
        int onay = JOptionPane.showConfirmDialog(null,
            "Rezervasyon iptal edilsin mi?\nMusteri: " + (c != null ? c.name : "?") +
            "\nOda: " + (room != null ? room.number : "?"),
            "Iptal Onayi", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (onay != JOptionPane.YES_OPTION) return;
        if (room != null) room.release(res.in, res.out);
        res.status = "IPTAL";
        active.remove(res);
        history.insert(res);
        refreshAll();
        info("Rezervasyon basariyla iptal edildi.");
    }

    static void doCheckout(String roomNum) {
        Room room = rooms.values().stream().filter(r -> r.number.equals(roomNum)).findFirst().orElse(null);
        if (room == null) return;

        Reservation res = active.stream().filter(r -> r.roomId.equals(room.id)).findFirst().orElse(null);
        if (res == null) { warn("Oda " + roomNum + " icin aktif rezervasyon yok."); return; }

        room.release(res.in, res.out);
        res.status = "COMPLETED";
        active.remove(res);
        history.insert(res); // BST'ye ekle: O(log n) ortalama surede dogru konuma yerlesir

        // Bekleme listesini tara, uygun ilk girisi aktiflestir
        Iterator<Reservation> it = waitlist.iterator();
        while (it.hasNext()) {
            Reservation w = it.next();
            if (w.roomId.equals(room.id) && room.isAvailable(w.in, w.out)) {
                room.book(w.in, w.out);
                w.status = "ACTIVE";
                active.add(w);
                it.remove();
                info("Bekleme listesindeki " + w.id + " rezervasyonu aktif hale getirildi.");
                break;
            }
        }
        refreshAll();
        info("Oda " + roomNum + " icin cikis islemi tamamlandi.");
    }

    // -------------------------------------------------------
    // TABLO GUNCELLEME — Her islemden sonra ekrandaki tablolari taze veriyle doldurur
    // -------------------------------------------------------

    // Uc tabloyu da tek seferde gunceller
    static void refreshAll() { refreshRooms(); refreshActive(); refreshWaitlist(); refreshHistory(); }

    static void refreshActive() {
        if (activeModel == null) return;
        activeModel.setRowCount(0);
        for (Reservation r : active) {
            Customer c  = custs.get(r.custId);
            Room     rm = rooms.get(r.roomId);
            activeModel.addRow(new Object[]{
                r.id,
                c  != null ? c.name  : "?",
                c  != null ? c.email : "?",
                rm != null ? rm.number : "?",
                rm != null ? rm.branch : "?",
                r.in.format(FMT), r.out.format(FMT)
            });
        }
    }

    static void refreshRooms() {
        if (roomModel == null) return;
        roomModel.setRowCount(0);
        rooms.values().forEach(r -> {
            boolean occupied = active.stream().anyMatch(res -> res.roomId.equals(r.id));
            roomModel.addRow(new Object[]{r.number, r.type, r.branch, occupied ? "Dolu" : "Musait"});
        });
    }

    static void refreshWaitlist() {
        if (waitModel == null) return;
        waitModel.setRowCount(0);
        for (Reservation r : waitlist) {
            Customer c  = custs.get(r.custId);
            Room     rm = rooms.get(r.roomId);
            waitModel.addRow(new Object[]{r.id, c != null ? c.name : "?", rm != null ? rm.number : "?", r.in.format(FMT), r.out.format(FMT)});
        }
    }

    static void refreshHistory() {
        if (histModel == null) return;
        histModel.setRowCount(0);
        // BST'nin inorder() metodu rezervasyonlari giris tarihine gore sirali dondurur
        for (Reservation r : history.inorder()) {
            Customer c  = custs.get(r.custId);
            Room     rm = rooms.get(r.roomId);
            histModel.addRow(new Object[]{r.id, c != null ? c.name : "?", rm != null ? rm.number : "?", r.in.format(FMT), r.out.format(FMT), r.status});
        }
    }

    // -------------------------------------------------------
    // KALICILIK — Veriler CSV dosyasina yazilir/okunur (harici kutuphane gerekmez)
    // Program her kapandiginda otomatik kaydeder, her acildiginda otomatik yukler.
    // Zaman: O(n) yazma/okuma  —  Alan: O(n)
    // -------------------------------------------------------

    static void saveData() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(FILE))) {
            rooms.values().forEach(r    -> pw.println("ROOM," + csv(r.id, r.number, r.type, r.branch)));
            custs.values().forEach(c    -> pw.println("CUST," + csv(c.id, c.name, c.email)));
            active.forEach(r            -> pw.println("RES,"  + csv(r.id, r.roomId, r.custId, r.in.format(FMT), r.out.format(FMT), r.status)));
            waitlist.forEach(r          -> pw.println("RES,"  + csv(r.id, r.roomId, r.custId, r.in.format(FMT), r.out.format(FMT), r.status)));
            history.inorder().forEach(r -> pw.println("RES,"  + csv(r.id, r.roomId, r.custId, r.in.format(FMT), r.out.format(FMT), r.status)));
        } catch (IOException e) { e.printStackTrace(); }
    }

    static void loadData() {
        File f = new File(FILE);
        if (!f.exists()) { seedData(); return; }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split(",", -1);
                if ("ROOM".equals(p[0])) {
                    rooms.put(p[1], new Room(p[1], p[2], p[3], p[4]));
                } else if ("CUST".equals(p[0])) {
                    custs.put(p[1], new Customer(p[1], p[2], p[3]));
                } else if ("RES".equals(p[0])) {
                    LocalDate ci = LocalDate.parse(p[4], FMT), co = LocalDate.parse(p[5], FMT);
                    Reservation r = new Reservation(p[1], p[2], p[3], ci, co, p[6]);
                    if ("ACTIVE".equals(p[6])) {
                        active.add(r);
                        Room rm = rooms.get(r.roomId);
                        if (rm != null) rm.book(ci, co);
                    } else if ("WAITLIST".equals(p[6])) {
                        waitlist.add(r);
                    } else {
                        history.insert(r);
                    }
                }
            }
        } catch (IOException e) { seedData(); }
    }

    static void seedData() {
        rooms.put("R1", new Room("R1", "101", "Standart",  "Sube A"));
        rooms.put("R2", new Room("R2", "102", "Deluxe",    "Sube A"));
        rooms.put("R3", new Room("R3", "103", "Suit",      "Sube A"));
        rooms.put("R4", new Room("R4", "201", "Standart",  "Sube B"));
        rooms.put("R5", new Room("R5", "202", "Deluxe",    "Sube B"));
        rooms.put("R6", new Room("R6", "203", "Suit",      "Sube B"));
        rooms.put("R7", new Room("R7", "301", "Standart",  "Sube C"));
        rooms.put("R8", new Room("R8", "302", "Penthouse", "Sube C"));

        custs.put("C1", new Customer("C1", "Alice Johnson", "alice@mail.com"));
        custs.put("C2", new Customer("C2", "Bob Smith",     "bob@mail.com"));
        custs.put("C3", new Customer("C3", "Carol White",   "carol@mail.com"));

        LocalDate today = LocalDate.now();
        Reservation r1 = new Reservation("RES001", "R1", "C1", today,               today.plusDays(3),  "ACTIVE");
        Reservation r2 = new Reservation("RES002", "R2", "C2", today.minusDays(10), today.minusDays(7), "COMPLETED");
        Reservation r3 = new Reservation("RES003", "R3", "C3", today.plusDays(2),   today.plusDays(5),  "ACTIVE");

        rooms.get("R1").book(r1.in, r1.out);
        rooms.get("R3").book(r3.in, r3.out);
        active.add(r1);
        active.add(r3);
        history.insert(r2);
    }

    // -------------------------------------------------------
    // YARDIMCI METOTLAR — Tekrar eden kucuk islemleri toplayan metodlar
    // -------------------------------------------------------

    // Uyari penceresi goster (sari ikon)
    static void warn(String msg) { JOptionPane.showMessageDialog(null, msg, "Uyari",  JOptionPane.WARNING_MESSAGE); }
    // Bilgi penceresi goster (mavi ikon)
    static void info(String msg) { JOptionPane.showMessageDialog(null, msg, "Bilgi",  JOptionPane.INFORMATION_MESSAGE); }

    // Verilen nesneleri virgullerle birlestirip tek satir CSV metni uretir
    static String csv(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) { if (i > 0) sb.append(','); sb.append(parts[i]); }
        return sb.toString();
    }

    // Duzenlenemez tablo modeli olusturur (kullanici hucrelere elle yazamaz)
    static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    static JTable styledTable(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(26);
        t.setGridColor(new Color(220, 225, 235));
        t.setSelectionBackground(new Color(174, 214, 241));
        t.getTableHeader().setBackground(new Color(210, 220, 240));
        t.getTableHeader().setForeground(Color.BLACK);
        t.getTableHeader().setFont(new Font("Arial", Font.BOLD, 12));
        t.getTableHeader().setPreferredSize(new Dimension(0, 30));
        t.getTableHeader().setOpaque(true);
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable tbl, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(tbl, v, sel, foc, row, col);
                if (sel) {
                    setBackground(new Color(174, 214, 241));
                    setForeground(Color.BLACK);
                } else {
                    setBackground(row % 2 == 0 ? Color.WHITE : new Color(235, 242, 252));
                    setForeground(Color.BLACK);
                }
                setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
                return this;
            }
        });
        return t;
    }

    static JPanel borderPanel(JComponent center, JComponent south) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(center, BorderLayout.CENTER);
        p.add(south,  BorderLayout.SOUTH);
        return p;
    }

    static JButton refreshBtn(java.awt.event.ActionListener l) {
        JButton b = new JButton("Yenile");
        b.addActionListener(l);
        return b;
    }

    // =======================================================
    // TERMINAL BOLUMU
    // =======================================================

    static final Scanner SC = new Scanner(System.in);

    static void terminalMenu() {
        while (true) {
            System.out.println("\n========================================");
            System.out.println("   E-OTEL REZERVASYON SISTEMI");
            System.out.println("========================================");
            System.out.println("  1. Yeni Rezervasyon Olustur");
            System.out.println("  2. Odalari Listele");
            System.out.println("  3. Aktif Rezervasyonlar");
            System.out.println("  4. Bekleme Listesi");
            System.out.println("  5. Gecmis Rezervasyonlar");
            System.out.println("  6. Rezervasyon Iptal Et");
            System.out.println("  7. Cikis Yap");
            System.out.println("========================================");
            System.out.print("  Seciminiz: ");
            switch (SC.nextLine().trim()) {
                case "1": tYeniRezervasyon();    break;
                case "2": tOdalariListele();      break;
                case "3": tAktifRezervasyonlar(); break;
                case "4": tBeklemeListesi();      break;
                case "5": tGecmis();              break;
                case "6": tIptal();               break;
                case "7": System.out.println("  Guvenli cikis..."); return;
                default:  System.out.println("  [!] 1-7 arasi bir sayi girin.");
            }
        }
    }

    static void tYeniRezervasyon() {
        System.out.println("\n--- Yeni Rezervasyon ---");
        System.out.print("Musteri Adi        : "); String name   = SC.nextLine().trim();
        System.out.print("Musteri E-posta    : "); String email  = SC.nextLine().trim();
        System.out.print("Oda Numarasi       : "); String rNum   = SC.nextLine().trim();
        System.out.print("Giris (gg-AA-yyyy) : "); String ciStr  = SC.nextLine().trim();
        System.out.print("Cikis  (gg-AA-yyyy): "); String coStr  = SC.nextLine().trim();
        System.out.print("Sube (bos=hepsi)   : "); String branch = SC.nextLine().trim();

        if (name.isEmpty() || email.isEmpty() || rNum.isEmpty() || ciStr.isEmpty() || coStr.isEmpty()) {
            System.out.println("  [!] Sube haric tum alanlar zorunludur."); return;
        }
        LocalDate ci, co;
        try { ci = LocalDate.parse(ciStr, FMT); co = LocalDate.parse(coStr, FMT); }
        catch (Exception e) { System.out.println("  [!] Gecersiz tarih. Ornek: 25-06-2026"); return; }
        if (!co.isAfter(ci)) { System.out.println("  [!] Cikis giris tarihinden sonra olmali."); return; }

        final String b = branch.isEmpty() ? null : branch;
        Room room = rooms.values().stream()
                .filter(r -> r.number.equals(rNum) && (b == null || r.branch.equalsIgnoreCase(b)))
                .findFirst().orElse(null);
        if (room == null) { System.out.println("  [!] Oda bulunamadi."); return; }

        String cid = "C" + System.currentTimeMillis();
        custs.put(cid, new Customer(cid, name, email));
        String rid = "R" + System.currentTimeMillis();
        Reservation res = new Reservation(rid, room.id, cid, ci, co, "");

        if (room.isAvailable(ci, co)) {
            room.book(ci, co); res.status = "ACTIVE"; active.add(res);
            System.out.println("  [OK] Rezervasyon olusturuldu! ID: " + rid);
        } else {
            res.status = "WAITLIST"; waitlist.add(res);
            System.out.println("  [!] Oda dolu. Bekleme listesine eklendi. ID: " + rid);
        }
    }

    static void tOdalariListele() {
        System.out.println("\n--- Odalar ---");
        System.out.printf("  %-6s %-12s %-12s %-8s%n", "Oda", "Tip", "Sube", "Durum");
        System.out.println("  " + "-".repeat(42));
        rooms.values().stream().sorted(Comparator.comparing(r -> r.number)).forEach(r -> {
            boolean dolu = active.stream().anyMatch(res -> res.roomId.equals(r.id));
            System.out.printf("  %-6s %-12s %-12s %-8s%n", r.number, r.type, r.branch, dolu ? "DOLU" : "MUSAIT");
        });
    }

    static void tAktifRezervasyonlar() {
        System.out.println("\n--- Aktif Rezervasyonlar ---");
        if (active.isEmpty()) { System.out.println("  Aktif rezervasyon yok."); return; }
        System.out.printf("  %-20s %-16s %-6s %-12s %-12s%n", "ID", "Musteri", "Oda", "Giris", "Cikis");
        System.out.println("  " + "-".repeat(68));
        for (Reservation r : active) {
            Customer c = custs.get(r.custId); Room rm = rooms.get(r.roomId);
            System.out.printf("  %-20s %-16s %-6s %-12s %-12s%n", r.id,
                c != null ? c.name : "?", rm != null ? rm.number : "?", r.in.format(FMT), r.out.format(FMT));
        }
    }

    static void tBeklemeListesi() {
        System.out.println("\n--- Bekleme Listesi ---");
        if (waitlist.isEmpty()) { System.out.println("  Bekleme listesi bos."); return; }
        System.out.printf("  %-20s %-16s %-6s %-12s %-12s%n", "ID", "Musteri", "Oda", "Giris", "Cikis");
        System.out.println("  " + "-".repeat(68));
        for (Reservation r : waitlist) {
            Customer c = custs.get(r.custId); Room rm = rooms.get(r.roomId);
            System.out.printf("  %-20s %-16s %-6s %-12s %-12s%n", r.id,
                c != null ? c.name : "?", rm != null ? rm.number : "?", r.in.format(FMT), r.out.format(FMT));
        }
    }

    static void tGecmis() {
        System.out.println("\n--- Gecmis Rezervasyonlar ---");
        List<Reservation> list = history.inorder();
        if (list.isEmpty()) { System.out.println("  Gecmis rezervasyon yok."); return; }
        System.out.printf("  %-20s %-16s %-6s %-12s %-12s %-10s%n", "ID", "Musteri", "Oda", "Giris", "Cikis", "Durum");
        System.out.println("  " + "-".repeat(78));
        for (Reservation r : list) {
            Customer c = custs.get(r.custId); Room rm = rooms.get(r.roomId);
            System.out.printf("  %-20s %-16s %-6s %-12s %-12s %-10s%n", r.id,
                c != null ? c.name : "?", rm != null ? rm.number : "?", r.in.format(FMT), r.out.format(FMT), r.status);
        }
    }

    static void tIptal() {
        tAktifRezervasyonlar();
        if (active.isEmpty()) return;
        System.out.print("\n  Iptal edilecek Rezervasyon ID: ");
        String resId = SC.nextLine().trim();
        Reservation res = active.stream().filter(r -> r.id.equals(resId)).findFirst().orElse(null);
        if (res == null) { System.out.println("  [!] Bulunamadi."); return; }
        Customer c = custs.get(res.custId); Room room = rooms.get(res.roomId);
        System.out.println("  Musteri: " + (c != null ? c.name : "?") + "  Oda: " + (room != null ? room.number : "?"));
        System.out.print("  Emin misiniz? (e/h): ");
        if (!"e".equalsIgnoreCase(SC.nextLine().trim())) { System.out.println("  Vazgecildi."); return; }
        if (room != null) room.release(res.in, res.out);
        res.status = "IPTAL"; active.remove(res); history.insert(res);
        Iterator<Reservation> it = waitlist.iterator();
        while (it.hasNext()) {
            Reservation w = it.next();
            if (room != null && w.roomId.equals(room.id) && room.isAvailable(w.in, w.out)) {
                room.book(w.in, w.out); w.status = "ACTIVE"; active.add(w); it.remove();
                System.out.println("  [OK] Bekleme: " + w.id + " aktif hale getirildi."); break;
            }
        }
        System.out.println("  [OK] Rezervasyon iptal edildi.");
    }
}
