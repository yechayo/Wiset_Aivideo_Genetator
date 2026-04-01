public class VerifyFusion {
    public static void main(String[] args) {
        final int FIXED_WIDTH = 1920;
        final int FIXED_HEIGHT = 1080;
        final int BOTTOM_BAR_HEIGHT = 180;
        final int MAIN_AREA_HEIGHT = FIXED_HEIGHT - BOTTOM_BAR_HEIGHT;
        final int PAD = 4;
        final int fCols = 3;

        System.out.println("=== Verifying Fusion Image 16:9 Implementation ===\n");

        for (int shotCount = 1; shotCount <= 9; shotCount++) {
            int fRows = (int) Math.ceil((double) shotCount / fCols);
            int cellW = (FIXED_WIDTH - PAD * (fCols + 1)) / fCols;
            int cellH = (MAIN_AREA_HEIGHT - PAD * (fRows + 1)) / fRows;

            System.out.println("Shots=" + shotCount + ", Rows=" + fRows);
            System.out.println("  cellW=" + cellW + ", cellH=" + cellH);

            int totalW = fCols * cellW + (fCols + 1) * PAD;
            int totalH = fRows * cellH + (fRows + 1) * PAD;

            System.out.println("  TotalW=" + totalW + " (<=1920? " + (totalW <= FIXED_WIDTH) + ")");
            System.out.println("  TotalH=" + totalH + " (<=900? " + (totalH <= MAIN_AREA_HEIGHT) + ")");

            if (totalW > FIXED_WIDTH || totalH > MAIN_AREA_HEIGHT) {
                System.out.println("  X OVERFLOW!");
            } else {
                System.out.println("  OK");
            }
            System.out.println();
        }
    }
}
