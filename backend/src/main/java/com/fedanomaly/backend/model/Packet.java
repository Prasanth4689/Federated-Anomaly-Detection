package com.fedanomaly.backend.model;

public class Packet {
    private String source;
    private String dest;
    private String protocol;
    private int sourcePort;
    private int destPort;
    private int bytes;
    private String label; // NORMAL or ATTACK

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDest() { return dest; }
    public void setDest(String dest) { this.dest = dest; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public int getSourcePort() { return sourcePort; }
    public void setSourcePort(int sourcePort) { this.sourcePort = sourcePort; }
    public int getDestPort() { return destPort; }
    public void setDestPort(int destPort) { this.destPort = destPort; }
    public int getBytes() { return bytes; }
    public void setBytes(int bytes) { this.bytes = bytes; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
