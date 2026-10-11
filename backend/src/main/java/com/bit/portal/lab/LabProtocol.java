package com.bit.portal.lab;

import jakarta.validation.constraints.*;

/** Public, bounded experiment metadata only. Never put targets, credentials or response bodies here. */
public record LabProtocol(@NotNull Family family, @NotNull Variant variant,
        @Min(0) @Max(3) int block, @Min(0) @Max(3) int order) {
    public enum Family { B01, T01, R01, R02, R03, R04, P01, P02, P04, C01, C02, C03, N01 }
    public enum Variant { DEFAULT, SHORT, LONG, FIXED, EXPONENTIAL, REUSED, FRESH }
    public boolean retry() { return family==Family.R01 || family==Family.R02 || family==Family.R03 || family==Family.R04; }
    public int maxRetries() { return family==Family.R03 ? 2 : retry() ? 1 : 0; }
    public int waitMs(int index) {
        return switch(family) {
            case R01 -> variant==Variant.LONG ? 10000 : 2000;
            case R02 -> 30000;
            case R03 -> variant==Variant.EXPONENTIAL ? 2000*(1<<(index-1)) : 2000;
            case R04 -> 10000;
            default -> 0;
        };
    }
    public int retryTimeoutMs(int initial) { return family==Family.R04 ? 60000 : initial; }
    public boolean valid() {
        if(family==null || variant==null || block<0 || block>3 || order<0 || order>3) return false;
        return switch(family) {
            case R01 -> (variant==Variant.SHORT || variant==Variant.LONG) && block>=1 && order>=1 && order<=2;
            case R03 -> (variant==Variant.FIXED || variant==Variant.EXPONENTIAL) && block>=1 && order>=1 && order<=2;
            case C01, C02 -> variant==Variant.DEFAULT && block>=1 && order>=1;
            case C03 -> variant==Variant.DEFAULT && block==1 && order>=1;
            case N01 -> (variant==Variant.REUSED || variant==Variant.FRESH) && block>=1 && block<=2 && order>=1 && order<=2;
            default -> variant==Variant.DEFAULT && block==0 && order==0;
        };
    }
}
