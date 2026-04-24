package com.devanshedutech.temp;

import java.sql.*;

public class DBCheck {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://devanshedutech-23681.j77.aws-ap-south-1.cockroachlabs.cloud:26257/devanshedutech?ssl=true&sslfactory=org.postgresql.ssl.NonValidatingFactory";
        String user = "anant";
        String password = "REDACTED-ROTATED-CREDENTIAL";
        
        System.out.println("Connecting to database...");
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            System.out.println("Connected successfully!\n");
            
            System.out.println("--- BROCHURE SETTINGS ---");
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT setting_key FROM app_setting WHERE setting_key LIKE 'GLOBAL_BROCHURE' OR setting_key LIKE 'COURSE_BROCHURE_%'");
                int count = 0;
                while (rs.next()) {
                    System.out.println("Found: " + rs.getString(1));
                    count++;
                }
                System.out.println("Total settings: " + count + "\n");
            }
            
            System.out.println("--- BROCHURE CHUNKS ---");
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT setting_key, COUNT(*) as count FROM brochure_chunks GROUP BY setting_key");
                int totalChunks = 0;
                while (rs.next()) {
                    String key = rs.getString("setting_key");
                    int count = rs.getInt("count");
                    System.out.println(" - " + key + ": " + count + " chunks");
                    totalChunks += count;
                }
                System.out.println("Total chunks: " + totalChunks);
            }
        } catch (Exception e) {
            System.err.println("Database Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
