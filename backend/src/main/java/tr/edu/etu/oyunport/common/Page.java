package tr.edu.etu.oyunport.common;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {

    public static int clampSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }
}
