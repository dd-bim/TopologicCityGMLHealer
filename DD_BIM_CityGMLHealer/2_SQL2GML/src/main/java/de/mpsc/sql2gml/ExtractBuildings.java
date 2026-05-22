package de.mpsc.sql2gml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Extracts specific buildings (by gml:id) from a CityGML file.
 * Keeps all metadata (header, boundedBy, etc.) intact.
 *
 * Usage: java -cp sql2gml-complete.jar de.mpsc.sql2gml.ExtractBuildings <input.gml> <output.gml> <ID1> <ID2> ...
 */
public class ExtractBuildings {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: ExtractBuildings <input.gml> <output.gml> <buildingId1> [buildingId2] ...");
            System.exit(1);
        }

        Path inputPath = Paths.get(args[0]);
        Path outputPath = Paths.get(args[1]);
        Set<String> targetIds = new HashSet<>(Arrays.asList(Arrays.copyOfRange(args, 2, args.length)));

        System.out.println("Input:  " + inputPath);
        System.out.println("Output: " + outputPath);
        System.out.println("Target IDs (" + targetIds.size() + "): " + targetIds);

        int totalBuildings = 0;
        int writtenBuildings = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(inputPath.toFile()), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outputPath.toFile()), StandardCharsets.UTF_8))) {

            String line;
            StringBuilder memberBlock = null;
            boolean inMember = false;

            while ((line = reader.readLine()) != null) {
                if (line.contains("<core:cityObjectMember>")) {
                    inMember = true;
                    memberBlock = new StringBuilder();
                    memberBlock.append(line).append("\n");
                } else if (inMember && line.contains("</core:cityObjectMember>")) {
                    memberBlock.append(line).append("\n");
                    totalBuildings++;

                    // Check if this block contains one of the target building IDs
                    String block = memberBlock.toString();
                    boolean match = false;
                    for (String id : targetIds) {
                        if (block.contains("gml:id=\"" + id + "\"")) {
                            match = true;
                            break;
                        }
                    }
                    if (match) {
                        writer.write(block);
                        writtenBuildings++;
                    }

                    inMember = false;
                    memberBlock = null;
                } else if (inMember) {
                    memberBlock.append(line).append("\n");
                } else if (line.contains("</core:CityModel>")) {
                    writer.write(line);
                    writer.newLine();
                } else {
                    // Header / boundedBy — copy as-is
                    writer.write(line);
                    writer.newLine();
                }
            }
        }

        System.out.println("Total buildings scanned: " + totalBuildings);
        System.out.println("Buildings written: " + writtenBuildings);
        System.out.println("Output: " + outputPath);
    }
}
