package de.mpsc.sql2gml.gui;

import de.mpsc.sql2gml.HealedReplaceWorkflow;
import de.mpsc.sql2gml.HealedReplaceWorkflow.GeometryMode;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Einfache Desktop-Oberfläche für {@link HealedReplaceWorkflow} — Datei-/Ordnerauswahl statt
 * Kommandozeile, für Nutzende ohne CMD/PowerShell (z.B. Projektpartner ohne Entwicklungsumgebung).
 * Ruft ausschließlich die bereits verifizierte Pipeline-Logik auf, keine eigene Neu-Implementierung.
 */
public final class Sql2GmlGui {

    private JFrame frame;
    private JRadioButton modeSingle;
    private JRadioButton modeBatch;
    private JLabel inputLabel;
    private JTextField inputField;
    private JTextField dbField;
    private JLabel outputLabel;
    private JTextField outputField;
    private JCheckBox polygonOnlyCheck;
    private JButton startButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JTextArea logArea;

    private final List<String> pendingLog = new ArrayList<>();

    private boolean darkMode = false;
    private JButton themeToggle;

    public static void main(String[] args) {
        // Muss vor der ersten Swing-Komponente gesetzt werden.
        com.formdev.flatlaf.FlatLightLaf.setup();

        Sql2GmlGui gui = new Sql2GmlGui();
        // Muss VOR dem ersten Zugriff auf HealedReplaceWorkflow passieren, da slf4j-simple
        // System.err beim Laden dieser Klasse einmalig fest bindet.
        installLogRedirect(gui::appendLog);
        SwingUtilities.invokeLater(gui::createAndShow);
    }

    private static void installLogRedirect(Consumer<String> lineConsumer) {
        System.setOut(new PrintStream(new LineTeeStream(System.out, lineConsumer), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineTeeStream(System.err, lineConsumer), true, StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI-Aufbau
    // ─────────────────────────────────────────────────────────────────────────

    private void createAndShow() {
        frame = new JFrame("sql2gml");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(0, 0));

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildHeaderPanel(), BorderLayout.NORTH);
        top.add(buildFormPanel(), BorderLayout.CENTER);
        frame.add(top, BorderLayout.NORTH);
        frame.add(buildLogPanel(), BorderLayout.CENTER);

        frame.setSize(760, 640);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        synchronized (pendingLog) {
            pendingLog.forEach(this::appendToArea);
            pendingLog.clear();
        }
    }

    private JPanel buildHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(10, 14, 0, 14));

        JLabel title = new JLabel("sql2gml");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        header.add(title, BorderLayout.WEST);

        themeToggle = new JButton(darkMode ? "☀" : "☾");
        themeToggle.setToolTipText(darkMode ? "Helles Design" : "Dunkles Design");
        themeToggle.setMargin(new Insets(2, 8, 2, 8));
        themeToggle.setFocusPainted(false);
        themeToggle.addActionListener(e -> toggleTheme());
        header.add(themeToggle, BorderLayout.EAST);

        return header;
    }

    private void toggleTheme() {
        darkMode = !darkMode;
        try {
            UIManager.setLookAndFeel(darkMode
                    ? new com.formdev.flatlaf.FlatDarkLaf()
                    : new com.formdev.flatlaf.FlatLightLaf());
            SwingUtilities.updateComponentTreeUI(frame);
            themeToggle.setText(darkMode ? "☀" : "☾");
            themeToggle.setToolTipText(darkMode ? "Helles Design" : "Dunkles Design");
        } catch (Exception e) {
            darkMode = !darkMode; // Wechsel rückgängig, falls die LAF nicht gesetzt werden konnte
        }
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(14, 14, 8, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;

        // Modus
        modeSingle = new JRadioButton("Einzelne Datei", true);
        modeBatch = new JRadioButton("Ordner (Batch)");
        ButtonGroup group = new ButtonGroup();
        group.add(modeSingle);
        group.add(modeBatch);
        modeSingle.addActionListener(e -> onModeChanged());
        modeBatch.addActionListener(e -> onModeChanged());

        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        modePanel.add(modeSingle);
        modePanel.add(modeBatch);
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(labeled("Modus", modePanel), c);
        row++;
        c.gridwidth = 1;

        // Eingabe
        inputLabel = new JLabel("CityGML-Datei:");
        inputField = new JTextField();
        JButton inputBrowse = new JButton("Durchsuchen…");
        inputBrowse.addActionListener(e -> chooseInput());
        row = addRow(panel, c, row, inputLabel, inputField, inputBrowse);

        // Datenbank
        dbField = new JTextField();
        JButton dbBrowse = new JButton("Durchsuchen…");
        dbBrowse.addActionListener(e -> chooseDatabase());
        row = addRow(panel, c, row, new JLabel("Healer-Datenbank (.db):"), dbField, dbBrowse);

        // Ausgabe
        outputLabel = new JLabel("Ausgabedatei (optional):");
        outputField = new JTextField();
        JButton outputBrowse = new JButton("Durchsuchen…");
        outputBrowse.addActionListener(e -> chooseOutput());
        row = addRow(panel, c, row, outputLabel, outputField, outputBrowse);

        // PolygonOnly-Checkbox
        polygonOnlyCheck = new JCheckBox(
                "Ohne TriangulatedSurface schreiben (Workaround für CityDoctor 3.18.2)");
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(polygonOnlyCheck, c);
        row++;

        // Start + Fortschritt
        startButton = new JButton("Konvertierung starten");
        startButton.addActionListener(e -> startConversion());
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(startButton, c);
        row++;

        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Bereit");
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(progressBar, c);
        row++;

        statusLabel = new JLabel(" ");
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(statusLabel, c);

        return panel;
    }

    private int addRow(JPanel panel, GridBagConstraints c, int row, JLabel label, JTextField field, JButton browse) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        panel.add(label, c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(browse, c);
        return row + 1;
    }

    private JPanel labeled(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(title + ":"), BorderLayout.WEST);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private JScrollPane buildLogPanel() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new EmptyBorder(0, 14, 14, 14));
        return scroll;
    }

    private void onModeChanged() {
        boolean single = modeSingle.isSelected();
        inputLabel.setText(single ? "CityGML-Datei:" : "Ordner mit CityGML-Dateien:");
        outputLabel.setText(single ? "Ausgabedatei (optional):" : "Ausgabeordner (optional):");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Datei-/Ordnerauswahl
    // ─────────────────────────────────────────────────────────────────────────

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        if (modeSingle.isSelected()) {
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CityGML (*.gml)", "gml"));
        } else {
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            inputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SQLite-Datenbank (*.db)", "db"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dbField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(modeSingle.isSelected()
                ? JFileChooser.FILES_ONLY : JFileChooser.DIRECTORIES_ONLY);
        int result = modeSingle.isSelected()
                ? chooser.showSaveDialog(frame)
                : chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            outputField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Konvertierung starten
    // ─────────────────────────────────────────────────────────────────────────

    private void startConversion() {
        String input = inputField.getText().trim();
        String db = dbField.getText().trim();
        String output = outputField.getText().trim();

        if (input.isEmpty() || db.isEmpty()) {
            JOptionPane.showMessageDialog(frame,
                    "Bitte Eingabe und Datenbank auswählen.", "Fehlende Angaben", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path inputPath = Path.of(input);
        Path dbPath = Path.of(db);
        boolean single = modeSingle.isSelected();
        if (!Files.exists(inputPath) || (single && Files.isDirectory(inputPath))
                || (!single && !Files.isDirectory(inputPath))) {
            JOptionPane.showMessageDialog(frame,
                    single ? "Eingabedatei nicht gefunden." : "Eingabeordner nicht gefunden.",
                    "Ungültige Eingabe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Files.isRegularFile(dbPath)) {
            JOptionPane.showMessageDialog(frame,
                    "Datenbankdatei nicht gefunden.", "Ungültige Eingabe", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add(input);
        args.add(db);
        if (!output.isEmpty()) {
            args.add(output);
        }
        String[] argsArray = args.toArray(new String[0]);

        HealedReplaceWorkflow.setGeometryMode(
                polygonOnlyCheck.isSelected() ? GeometryMode.ALWAYS_POLYGON : GeometryMode.AS_IN_DATABASE);

        setFormEnabled(false);
        logArea.setText("");
        progressBar.setIndeterminate(true);
        progressBar.setString("Läuft…");
        statusLabel.setText("Verarbeitung gestartet…");

        HealedReplaceWorkflow.setProgressListener(new HealedReplaceWorkflow.ProgressListener() {
            @Override
            public void onFileStart(int fileIndex, int totalFiles, String filename) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(totalFiles);
                    progressBar.setValue(fileIndex - 1);
                    progressBar.setString(fileIndex + " / " + totalFiles);
                    statusLabel.setText("Datei " + fileIndex + " von " + totalFiles + ": " + filename);
                });
            }

            @Override
            public void onFeature(int featuresRead) {
                if (single) {
                    // Echtes X/Y: Maximum kommt aus dem Vorab-Zähllauf unten.
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(featuresRead);
                        progressBar.setString(featuresRead + " / " + progressBar.getMaximum());
                    });
                } else if (featuresRead % 25 == 0) {
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Verarbeitet: " + featuresRead + " Features"));
                }
            }
        });

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (single) {
                    // Kurzer Vorab-Zähllauf (nur Zeilen mit dem Member-Öffnungstag, dieselbe
                    // Annahme wie GmlMemberFilter) für einen echten X/Y-Balken statt "unbestimmt".
                    int total = countCityObjectMembers(inputPath);
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setMaximum(Math.max(total, 1));
                        progressBar.setValue(0);
                        progressBar.setString("0 / " + total);
                    });
                }
                HealedReplaceWorkflow.run(argsArray);
                return null;
            }

            @Override
            protected void done() {
                HealedReplaceWorkflow.setProgressListener(null);
                setFormEnabled(true);
                progressBar.setIndeterminate(false);
                try {
                    get();
                    progressBar.setValue(progressBar.getMaximum());
                    progressBar.setString("Fertig");
                    statusLabel.setText("Erfolgreich abgeschlossen.");
                    onSuccess(single, output, inputPath);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    progressBar.setValue(0);
                    progressBar.setString("Fehler");
                    statusLabel.setText("Fehler: " + cause.getMessage());
                    JOptionPane.showMessageDialog(frame,
                            "Die Verarbeitung ist fehlgeschlagen:\n" + cause.getMessage(),
                            "Fehler", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void onSuccess(boolean single, String output, Path inputPath) {
        Path resultFolder;
        if (!output.isEmpty()) {
            Path outPath = Path.of(output);
            resultFolder = single ? outPath.getParent() : outPath;
        } else {
            resultFolder = single ? inputPath.getParent() : inputPath;
        }
        if (resultFolder == null || !Desktop.isDesktopSupported()) return;
        int choice = JOptionPane.showConfirmDialog(frame,
                "Fertig! Ausgabeordner jetzt öffnen?", "sql2gml",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            try {
                Desktop.getDesktop().open(resultFolder.toFile());
            } catch (IOException ignored) {
                // Kein Blocker — Nutzer kann den Ordner auch manuell öffnen.
            }
        }
    }

    /** Zählt CityGML-Features (Vorab-Scan für einen echten X/Y-Balken bei Einzeldateien).
     *  Gleiche Annahme wie {@link de.mpsc.sql2gml.GmlMemberFilter}: das Member-Tag steht auf
     *  einer eigenen Zeile — reines Zeilenlesen, kein XML-Parsing, daher schnell auch bei
     *  großen Kacheln. */
    private static int countCityObjectMembers(Path file) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("<core:cityObjectMember>")) count++;
            }
        }
        return count;
    }

    private void setFormEnabled(boolean enabled) {
        modeSingle.setEnabled(enabled);
        modeBatch.setEnabled(enabled);
        inputField.setEnabled(enabled);
        dbField.setEnabled(enabled);
        outputField.setEnabled(enabled);
        polygonOnlyCheck.setEnabled(enabled);
        startButton.setEnabled(enabled);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log-Anzeige
    // ─────────────────────────────────────────────────────────────────────────

    private void appendLog(String line) {
        if (logArea == null) {
            synchronized (pendingLog) {
                pendingLog.add(line);
            }
            return;
        }
        SwingUtilities.invokeLater(() -> appendToArea(line));
    }

    private void appendToArea(String line) {
        logArea.append(line);
        logArea.append("\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Spiegelt jede geschriebene Zeile zusätzlich an einen Consumer, ohne den Original-Stream zu stoppen. */
    private static final class LineTeeStream extends OutputStream {
        private final OutputStream original;
        private final Consumer<String> lineConsumer;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        LineTeeStream(OutputStream original, Consumer<String> lineConsumer) {
            this.original = original;
            this.lineConsumer = lineConsumer;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            original.write(b);
            if (b == '\n') {
                lineConsumer.accept(buffer.toString(StandardCharsets.UTF_8));
                buffer.reset();
            } else if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public void flush() throws IOException {
            original.flush();
        }
    }
}
