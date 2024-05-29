package me.srrapero720.watermedia.runtime;

public class UnsupportedArchitechtureException extends UnsupportedOperationException {
    public UnsupportedArchitechtureException() {
        super("32 bits are not supported");
    }
}
