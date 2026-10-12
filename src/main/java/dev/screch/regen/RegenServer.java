package dev.screch.regen;

public interface RegenServer {
    boolean regen$queue(long seed);
    void regen$runPending();
    boolean regen$isRebuilding();
}
