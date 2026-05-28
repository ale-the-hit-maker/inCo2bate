package com.incusense.model;

public final class Entities {

    private Entities() {
    }

    public enum Metric {
        CO2_PPM("co2_ppm", "CO2", "ppm"),
        HEATER_TEMP("heater_temp", "Heater temperature", "C"),
        ENV_TEMP("env_temp", "Environment temperature", "C"),
        ENV_HUM("env_hum", "Environment humidity", "%"),
        RAIL_12V("rail_12v", "12V rail", "V");

        private final String fieldName;
        private final String label;
        private final String unit;

        Metric(String fieldName, String label, String unit) {
            this.fieldName = fieldName;
            this.label = label;
            this.unit = unit;
        }

        public String fieldName() {
            return fieldName;
        }

        public String label() {
            return label;
        }

        public String unit() {
            return unit;
        }
    }
}
