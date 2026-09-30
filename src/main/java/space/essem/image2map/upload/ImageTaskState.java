package space.essem.image2map.upload;

import java.io.IOException;
import java.util.UUID;

/** One instance per connection. Only the server thread mutates it. */
public final class ImageTaskState {
    public enum Phase { IDLE, CLIENT_SELECTING_IMAGE, GETTING_IMAGE, PROCESSING_IMAGE, PREVIEWING }
    private Phase phase = Phase.IDLE;
    private UUID requestId;
    private long deadline;
    private long nextAllowed = Long.MIN_VALUE;

    public Phase phase() { return phase; }
    public UUID requestId() { return requestId; }
    public boolean matches(UUID id) { return requestId != null && requestId.equals(id); }

    public UUID begin(long now) throws IOException {
        if (phase != Phase.IDLE || requestId != null) throw new IOException("You already have an image operation or preview open");
        if (now < nextAllowed) throw new IOException("Please wait before starting another image operation");
        requestId = UUID.randomUUID();
        return requestId;
    }

    public void transition(Phase next, long now, int timeoutSeconds) {
        boolean allowed = switch (phase) {
            case IDLE -> requestId != null && (next == Phase.CLIENT_SELECTING_IMAGE || next == Phase.GETTING_IMAGE);
            case CLIENT_SELECTING_IMAGE -> next == Phase.GETTING_IMAGE;
            case GETTING_IMAGE -> next == Phase.PROCESSING_IMAGE;
            case PROCESSING_IMAGE -> next == Phase.PREVIEWING;
            case PREVIEWING -> false;
        };
        if (!allowed) throw new IllegalStateException("Invalid image task transition: " + phase + " -> " + next);
        phase = next;
        // Only acquisition can expire. Picking, confirmation, rendering and previews have no deadline.
        deadline = next == Phase.GETTING_IMAGE && timeoutSeconds > 0
                ? now + timeoutSeconds * 1_000_000_000L : Long.MAX_VALUE;
    }

    public boolean expired(long now) { return phase != Phase.IDLE && now >= deadline; }

    public void finish(long now, int cooldownSeconds) {
        phase = Phase.IDLE;
        requestId = null;
        deadline = 0;
        nextAllowed = now + cooldownSeconds * 1_000_000_000L;
    }
}
