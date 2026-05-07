package it.unipd.dei.se.nexa.parser;

public final class ParserStats {

    private int totalRecords;
    private int validRecords;
    private int skippedRecords;
    private int missingIdentifierRecords;
    private int missingTitleRecords;
    private int missingAbstractRecords;
    private int missingTextRecords;
    private int missingGoldRecords;

    public void recordTotal() {
        totalRecords++;
    }

    public void recordValid() {
        validRecords++;
    }

    public void recordSkipped() {
        skippedRecords++;
    }

    public void recordMissingIdentifier() {
        missingIdentifierRecords++;
    }

    public void recordMissingTitle() {
        missingTitleRecords++;
    }

    public void recordMissingAbstract() {
        missingAbstractRecords++;
    }

    public void recordMissingText() {
        missingTextRecords++;
    }

    public void recordMissingGold() {
        missingGoldRecords++;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public int getValidRecords() {
        return validRecords;
    }

    public int getSkippedRecords() {
        return skippedRecords;
    }

    public int getMissingIdentifierRecords() {
        return missingIdentifierRecords;
    }

    public int getMissingTitleRecords() {
        return missingTitleRecords;
    }

    public int getMissingAbstractRecords() {
        return missingAbstractRecords;
    }

    public int getMissingTextRecords() {
        return missingTextRecords;
    }

    public int getMissingGoldRecords() {
        return missingGoldRecords;
    }

    public void reset() {
        totalRecords = 0;
        validRecords = 0;
        skippedRecords = 0;
        missingIdentifierRecords = 0;
        missingTitleRecords = 0;
        missingAbstractRecords = 0;
        missingTextRecords = 0;
        missingGoldRecords = 0;
    }

    @Override
    public String toString() {
        return "ParserStats{" +
                "totalRecords=" + totalRecords +
                ", validRecords=" + validRecords +
                ", skippedRecords=" + skippedRecords +
                ", missingIdentifierRecords=" + missingIdentifierRecords +
                ", missingTitleRecords=" + missingTitleRecords +
                ", missingAbstractRecords=" + missingAbstractRecords +
                ", missingTextRecords=" + missingTextRecords +
                ", missingGoldRecords=" + missingGoldRecords +
                '}';
    }
}
