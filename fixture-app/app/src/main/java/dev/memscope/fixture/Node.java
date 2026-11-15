package dev.memscope.fixture;

/** Small linked node used by the inflate action. */
public class Node {
    public final Node next;
    public final byte[] payload = new byte[32];
    public Node(Node next) { this.next = next; }
}
