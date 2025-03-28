package org.currierg.generators.pojos;

public class UnsignedInt {
    private long value;

    public UnsignedInt(long value) {
        if (value < 0 || value > 4294967295L) {
            throw new IllegalArgumentException("Value must be between 0 and 2^32-1");
        }
        this.value = value;
    }

    public long getValue() { return value; }
    public void setValue(long value) { this.value = value; }  // Add bounds check if needed
    @Override
    public String toString() { return String.valueOf(value); }
}