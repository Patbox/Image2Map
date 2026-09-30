package space.essem.image2map.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import space.essem.image2map.image.ImageSafety;
import space.essem.image2map.upload.UploadBuffer;

import java.util.List;
import java.util.UUID;

/** Common wire types. No client classes may be referenced here. */
public final class UploadPayloads {
    private UploadPayloads() { }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("image2map", name));
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Capabilities.TYPE, Capabilities.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Metadata.TYPE, Metadata.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Complete.TYPE, Complete.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cancel.TYPE, Cancel.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Status.TYPE, Status.CODEC);
    }

    public record Capabilities(boolean clientUpload) implements CustomPacketPayload {
        public static final Type<Capabilities> TYPE = id("capabilities_v1");
        public static final StreamCodec<FriendlyByteBuf, Capabilities> CODEC = StreamCodec.of(
                (b, p) -> b.writeBoolean(p.clientUpload), b -> new Capabilities(b.readBoolean()));
        @Override public Type<Capabilities> type() { return TYPE; }
    }

    public record Request(UUID requestId, String path, String mode, ImageSafety.Limits limits, int timeoutSeconds) implements CustomPacketPayload {
        public static final Type<Request> TYPE = id("upload_request_v1");
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.requestId);
            b.writeUtf(p.path, 4096);
            b.writeUtf(p.mode, 16);
            b.writeLong(p.limits.maxBytes());
            b.writeUtf(String.join(",", p.limits.formats()), 1024);
            b.writeInt(p.timeoutSeconds);
        }, b -> new Request(b.readUUID(), b.readUtf(4096), b.readUtf(16),
                new ImageSafety.Limits(b.readLong(), List.of(b.readUtf(1024).split(","))), b.readInt()));
        @Override public Type<Request> type() { return TYPE; }
    }

    public record Metadata(UUID requestId, String format, long byteSize, int width, int height, String mode) implements CustomPacketPayload {
        public static final Type<Metadata> TYPE = id("upload_metadata_v1");
        public static final StreamCodec<FriendlyByteBuf, Metadata> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.requestId);
            b.writeUtf(p.format, 32);
            b.writeLong(p.byteSize);
            b.writeInt(p.width);
            b.writeInt(p.height);
            b.writeUtf(p.mode, 16);
        }, b -> new Metadata(b.readUUID(), b.readUtf(32), b.readLong(), b.readInt(), b.readInt(), b.readUtf(16)));
        public ImageSafety.Info info() { return new ImageSafety.Info(format, width, height); }
        @Override public Type<Metadata> type() { return TYPE; }
    }

    public record Chunk(UUID requestId, int sequence, byte[] bytes) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = id("upload_chunk_v1");
        public static final StreamCodec<FriendlyByteBuf, Chunk> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.requestId);
            b.writeVarInt(p.sequence);
            b.writeByteArray(p.bytes);
        }, b -> new Chunk(b.readUUID(), b.readVarInt(), b.readByteArray(UploadBuffer.CHUNK_SIZE)));
        @Override public Type<Chunk> type() { return TYPE; }
    }

    public record Complete(UUID requestId) implements CustomPacketPayload {
        public static final Type<Complete> TYPE = id("upload_complete_v1");
        public static final StreamCodec<FriendlyByteBuf, Complete> CODEC = StreamCodec.of(
                (b, p) -> b.writeUUID(p.requestId), b -> new Complete(b.readUUID()));
        @Override public Type<Complete> type() { return TYPE; }
    }

    public record Cancel(UUID requestId, String reason) implements CustomPacketPayload {
        public static final Type<Cancel> TYPE = id("upload_cancel_v1");
        public static final StreamCodec<FriendlyByteBuf, Cancel> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.requestId);
            b.writeUtf(p.reason, 512);
        }, b -> new Cancel(b.readUUID(), b.readUtf(512)));
        @Override public Type<Cancel> type() { return TYPE; }
    }

    public record Status(UUID requestId, boolean accepted, boolean terminal, String message) implements CustomPacketPayload {
        public static final Type<Status> TYPE = id("upload_status_v1");
        public static final StreamCodec<FriendlyByteBuf, Status> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.requestId);
            b.writeBoolean(p.accepted);
            b.writeBoolean(p.terminal);
            b.writeUtf(p.message, 512);
        }, b -> new Status(b.readUUID(), b.readBoolean(), b.readBoolean(), b.readUtf(512)));
        @Override public Type<Status> type() { return TYPE; }
    }
}
