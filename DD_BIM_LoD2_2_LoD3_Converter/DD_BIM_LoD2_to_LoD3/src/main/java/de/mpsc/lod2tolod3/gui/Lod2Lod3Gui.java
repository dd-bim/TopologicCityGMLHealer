package de.mpsc.lod2tolod3.gui;

import de.mpsc.lod2tolod3.Lod2ToLod3Pipeline;
import de.mpsc.lod2tolod3.Lod2ToLod3Pipeline.StepSelection;

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
 * Einfache Desktop-Oberfläche für {@link Lod2ToLod3Pipeline} — Datei-/Ordnerauswahl statt
 * Kommandozeile, für Nutzende ohne CMD/PowerShell (z.B. Projektpartner ohne Entwicklungsumgebung).
 * Ruft ausschließlich die bereits verifizierte Pipeline-Logik auf, keine eigene Neu-Implementierung.
 */
public final class Lod2Lod3Gui {

    private JFrame frame;
    private JRadioButton modeSingle;
    private JRadioButton modeBatch;
    private JLabel inputLabel;
    private JTextField inputField;
    private JTextField jsonField;
    private JTextField dgmField;
    private JLabel outputLabel;
    private JTextField outputField;
    private JButton startButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JTextArea logArea;

    private JButton advancedToggle;
    private JPanel advancedPanel;
    private boolean advancedExpanded = false;
    private JCheckBox basementCheck;
    private JCheckBox storeysCheck;
    private JCheckBox doorsCheck;
    private JCheckBox windowsCheck;
    private JCheckBox balconiesCheck;
    private JCheckBox roofWindowsCheck;

    private final List<String> pendingLog = new ArrayList<>();

    private boolean darkMode = false;
    private JButton themeToggle;

    public static void main(String[] args) {
        // Muss vor der ersten Swing-Komponente gesetzt werden.
        com.formdev.flatlaf.FlatLightLaf.setup();

        Lod2Lod3Gui gui = new Lod2Lod3Gui();
        // Muss VOR dem ersten Zugriff auf Lod2ToLod3Pipeline passieren, da slf4j-simple
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
        frame = new JFrame("LoD2 → LoD3");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(0, 0));

        JPanel top = new JPanel(new BorderLayout());
        top.add(buildHeaderPanel(), BorderLayout.NORTH);
        top.add(buildFormPanel(), BorderLayout.CENTER);
        frame.add(top, BorderLayout.NORTH);
        frame.add(buildLogPanel(), BorderLayout.CENTER);

        frame.setSize(780, 700);
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

        JLabel title = new JLabel("LoD2 → LoD3");
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

        // JSON-Modulordner
        jsonField = new JTextField();
        JButton jsonBrowse = new JButton("Durchsuchen…");
        jsonBrowse.addActionListener(e -> chooseFolder(jsonField));
        row = addRow(panel, c, row, new JLabel("Baukörpermodule (JSON-Ordner):"), jsonField, jsonBrowse);

        // DGM (optional)
        dgmField = new JTextField();
        JButton dgmBrowse = new JButton("Durchsuchen…");
        dgmBrowse.addActionListener(e -> chooseDgm());
        row = addRow(panel, c, row, new JLabel("DGM (optional):"), dgmField, dgmBrowse);

        // Ausgabe
        outputLabel = new JLabel("Ausgabeordner:");
        outputField = new JTextField();
        JButton outputBrowse = new JButton("Durchsuchen…");
        outputBrowse.addActionListener(e -> chooseFolder(outputField));
        row = addRow(panel, c, row, outputLabel, outputField, outputBrowse);

        // Erweiterte Optionen (aufklappbar)
        c.gridx = 0; c.gridy = row; c.gridwidth = 3;
        panel.add(buildAdvancedSection(), c);
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

    private JPanel buildAdvancedSection() {
        JPanel wrapper = new JPanel();
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));

        advancedToggle = new JButton("▸ Erweiterte Optionen (Schritte auswählen)");
        advancedToggle.setHorizontalAlignment(SwingConstants.LEFT);
        advancedToggle.setBorderPainted(false);
        advancedToggle.setContentAreaFilled(false);
        advancedToggle.setFocusPainted(false);
        advancedToggle.setMargin(new Insets(2, 0, 2, 0));
        advancedToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        advancedToggle.addActionListener(e -> toggleAdvanced());
        advancedToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        wrapper.add(advancedToggle);

        advancedPanel = new JPanel();
        advancedPanel.setLayout(new BoxLayout(advancedPanel, BoxLayout.Y_AXIS));
        advancedPanel.setBorder(new EmptyBorder(2, 22, 4, 4));
        advancedPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        basementCheck = new JCheckBox("Keller", true);
        storeysCheck = new JCheckBox("Geschosse", true);
        doorsCheck = new JCheckBox("Türen (inkl. Ersatztüren)", true);
        windowsCheck = new JCheckBox("Fenster", true);
        balconiesCheck = new JCheckBox("Balkone", true);
        roofWindowsCheck = new JCheckBox("Dachfenster", true);
        String needsStoreysTip = "Braucht „Geschosse“ — ohne Geschosse gibt es keine eigene "
                + "Erdgeschoss-/Obergeschoss-Wand, an der dieser Schritt ansetzen könnte.";
        doorsCheck.setToolTipText(needsStoreysTip);
        windowsCheck.setToolTipText(needsStoreysTip);
        balconiesCheck.setToolTipText(needsStoreysTip);
        storeysCheck.addActionListener(e -> onStoreysToggled());
        for (JCheckBox cb : List.of(basementCheck, storeysCheck, doorsCheck, windowsCheck, balconiesCheck, roofWindowsCheck)) {
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            advancedPanel.add(cb);
        }

        JLabel hint = new JLabel("<html><i>Türen, Fenster und Balkone sind an Geschosse gekoppelt "
                + "(sie platzieren auf den Geschoss-Wänden) — ohne Geschosse werden sie automatisch "
                + "mit-abgewählt und gesperrt. Balkone wirken am besten zusammen mit Fenstern.</i></html>");
        hint.setFont(hint.getFont().deriveFont(11f));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(6, 0, 0, 0));
        advancedPanel.add(hint);

        advancedPanel.setVisible(false);
        wrapper.add(advancedPanel);
        return wrapper;
    }

    // Merkt sich den Haken-Zustand von Tueren/Fenster/Balkone, waehrend Geschosse abgewaehlt ist,
    // damit er beim erneuten Anhaken von Geschosse wiederhergestellt wird (statt die drei einfach
    // dauerhaft auf "an" zurueckzusetzen).
    private boolean doorsPriorState = true;
    private boolean windowsPriorState = true;
    private boolean balconiesPriorState = true;

    private void onStoreysToggled() {
        boolean storeysOn = storeysCheck.isSelected();
        if (!storeysOn) {
            doorsPriorState = doorsCheck.isSelected();
            windowsPriorState = windowsCheck.isSelected();
            balconiesPriorState = balconiesCheck.isSelected();
            doorsCheck.setSelected(false);
            windowsCheck.setSelected(false);
            balconiesCheck.setSelected(false);
        } else {
            doorsCheck.setSelected(doorsPriorState);
            windowsCheck.setSelected(windowsPriorState);
            balconiesCheck.setSelected(balconiesPriorState);
        }
        doorsCheck.setEnabled(storeysOn);
        windowsCheck.setEnabled(storeysOn);
        balconiesCheck.setEnabled(storeysOn);
    }

    private void toggleAdvanced() {
        advancedExpanded = !advancedExpanded;
        advancedPanel.setVisible(advancedExpanded);
        advancedToggle.setText((advancedExpanded ? "▾" : "▸") + " Erweiterte Optionen (Schritte auswählen)");
        frame.revalidate();
        frame.repaint();
    }

    private StepSelection currentSelection() {
        return new StepSelection(
                basementCheck.isSelected(), storeysCheck.isSelected(), doorsCheck.isSelected(),
                windowsCheck.isSelected(), balconiesCheck.isSelected(), roofWindowsCheck.isSelected());
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

    private void chooseFolder(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseDgm() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "DGM (.asc, .tif, .tiff, .zip) oder Ordner mit mehreren Kacheln", "asc", "tif", "tiff", "zip"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dgmField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Konvertierung starten
    // ─────────────────────────────────────────────────────────────────────────

    private void startConversion() {
        String input = inputField.getText().trim();
        String json = jsonField.getText().trim();
        String dgm = dgmField.getText().trim();
        String output = outputField.getText().trim();

        if (input.isEmpty() || json.isEmpty() || output.isEmpty()) {
            JOptionPane.showMessageDialog(frame,
                    "Bitte Eingabe, JSON-Modulordner und Ausgabeordner auswählen.",
                    "Fehlende Angaben", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path inputPath = Path.of(input);
        boolean single = modeSingle.isSelected();
        if (!Files.exists(inputPath) || (single && Files.isDirectory(inputPath))
                || (!single && !Files.isDirectory(inputPath))) {
            JOptionPane.showMessageDialog(frame,
                    single ? "Eingabedatei nicht gefunden." : "Eingabeordner nicht gefunden.",
                    "Ungültige Eingabe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Files.isDirectory(Path.of(json))) {
            JOptionPane.showMessageDialog(frame,
                    "JSON-Modulordner nicht gefunden.", "Ungültige Eingabe", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!dgm.isEmpty() && !Files.exists(Path.of(dgm))) {
            JOptionPane.showMessageDialog(frame,
                    "DGM-Pfad nicht gefunden.", "Ungültige Eingabe", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> args = new ArrayList<>();
        args.add(input);
        args.add(json);
        args.add(output);
        if (!dgm.isEmpty()) {
            args.add(dgm);
        }
        String[] argsArray = args.toArray(new String[0]);
        StepSelection selection = currentSelection();

        setFormEnabled(false);
        logArea.setText("");
        progressBar.setIndeterminate(true);
        progressBar.setString("Läuft…");
        statusLabel.setText("Verarbeitung gestartet…");

        Lod2ToLod3Pipeline.setProgressListener(new Lod2ToLod3Pipeline.ProgressListener() {
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
            public void onBuilding(int buildingsProcessed) {
                if (single) {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(buildingsProcessed);
                        progressBar.setString(buildingsProcessed + " / " + progressBar.getMaximum());
                    });
                } else if (buildingsProcessed % 25 == 0) {
                    SwingUtilities.invokeLater(() -> statusLabel.setText("Verarbeitet: " + buildingsProcessed + " Gebäude"));
                }
            }
        });

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (single) {
                    // Kurzer Vorab-Zaehllauf fuer einen echten X/Y-Balken statt "unbestimmt".
                    int total = countCityObjectMembers(inputPath);
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setIndeterminate(false);
                        progressBar.setMaximum(Math.max(total, 1));
                        progressBar.setValue(0);
                        progressBar.setString("0 / " + total);
                    });
                }
                Lod2ToLod3Pipeline.run(argsArray, selection);
                return null;
            }

            @Override
            protected void done() {
                Lod2ToLod3Pipeline.setProgressListener(null);
                setFormEnabled(true);
                progressBar.setIndeterminate(false);
                try {
                    get();
                    progressBar.setValue(progressBar.getMaximum());
                    progressBar.setString("Fertig");
                    statusLabel.setText("Erfolgreich abgeschlossen.");
                    onSuccess(output);
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

    private void onSuccess(String output) {
        if (!Desktop.isDesktopSupported()) return;
        int choice = JOptionPane.showConfirmDialog(frame,
                "Fertig! Ausgabeordner jetzt öffnen?", "LoD2 → LoD3",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            try {
                Desktop.getDesktop().open(Path.of(output).toFile());
            } catch (IOException ignored) {
                // Kein Blocker — Nutzer kann den Ordner auch manuell öffnen.
            }
        }
    }

    private void setFormEnabled(boolean enabled) {
        modeSingle.setEnabled(enabled);
        modeBatch.setEnabled(enabled);
        inputField.setEnabled(enabled);
        jsonField.setEnabled(enabled);
        dgmField.setEnabled(enabled);
        outputField.setEnabled(enabled);
        basementCheck.setEnabled(enabled);
        storeysCheck.setEnabled(enabled);
        doorsCheck.setEnabled(enabled);
        windowsCheck.setEnabled(enabled);
        balconiesCheck.setEnabled(enabled);
        roofWindowsCheck.setEnabled(enabled);
        startButton.setEnabled(enabled);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log-Anzeige
    // ─────────────────────────────────────────────────────────────────────────

    /** Zaehlt CityGML-Features (Vorab-Scan fuer einen echten X/Y-Balken bei Einzeldateien).
     *  Gleiche Annahme wie beim sql2gml-Pendant: das Member-Tag steht auf einer eigenen Zeile —
     *  reines Zeilenlesen, kein XML-Parsing, daher schnell auch bei grossen Kacheln. */
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
