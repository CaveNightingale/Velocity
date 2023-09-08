/*
 * Copyright (C) 2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet.brigadier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Preconditions;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * A root command node that allow appending new nodes to the root node.
 * But not allow reading or removing nodes from the root node, because
 * we don't know how to deserialize the original data.
 * Note that this class doesnot check duplicated nodes.
 * But some good news is that the minecraft client, who is able to deserialize 
 * the original data, will merge the duplicated nodes.
 */
public class AppendOnlyRootCommandNode extends RootCommandNode<CommandSource> {
  private static final byte NODE_TYPE_LITERAL = 0x01;
  private static final byte NODE_TYPE_ARGUMENT = 0x02;

  private static final byte FLAG_EXECUTABLE = 0x04;
  private static final byte FLAG_IS_REDIRECT = 0x08;
  private static final byte FLAG_HAS_SUGGESTIONS = 0x10;

	private final int originalCount;
  /**
   * The original data, excluding the deserialized fields.
   */
	private final byte[] originalData;
  private byte flag;
  private int[] originChildren;
  private OptionalInt originRedirect;

  private AppendOnlyRootCommandNode(ByteBuf buf) {
    super();
    // It's possible that the root node to be deserialized at proxy side since root node has no argument type.
    // However, most of the nodes are not deserializable at proxy side, since argument types are not registered at proxy side.
    this.originalCount = ProtocolUtils.readVarInt(buf);
    this.flag = buf.readByte();
    originChildren = ProtocolUtils.readIntegerArray(buf);
    if ((flag & FLAG_IS_REDIRECT) != 0) {
      originRedirect = OptionalInt.of(ProtocolUtils.readVarInt(buf));
    } else {
      originRedirect = OptionalInt.empty();
    }
    originalData = new byte[buf.readableBytes() - 1];
    buf.readBytes(originalData);
    // Assert that the last byte is 0x00, or called VarInt(0)
    Preconditions.checkArgument(buf.readByte() == 0x00, "command packet must end with 0x00");
  }

  /**
   * Create a new {@link AppendOnlyRootCommandNode} from the packet.
   * Since we don't parse the original data, protocol version is not required.
   * @param buf the packet
   * @return the new {@link AppendOnlyRootCommandNode}
   */
  public static AppendOnlyRootCommandNode decodePacket(ByteBuf buf) {
    return new AppendOnlyRootCommandNode(buf);
  }

  public void encodePacket(ByteBuf buf, ProtocolVersion version) {
    Object2IntOpenHashMap<CommandNode<CommandSource>> newlyAddedNodes = new Object2IntOpenHashMap<>();
	  int idx = originalCount;
    ArrayList<CommandNode<CommandSource>> toEncode = new ArrayList<>();
    ArrayDeque<CommandNode<CommandSource>> queue = new ArrayDeque<>(getChildren());
    if (getRedirect() != null) {
      queue.add(getRedirect());
    }
    while (!queue.isEmpty()) {
      CommandNode<CommandSource> node = queue.poll();
      if (newlyAddedNodes.containsKey(node)) {
        continue;
      }
      newlyAddedNodes.put(node, idx++);
      toEncode.add(node);
      queue.addAll(node.getChildren());
      if (node.getRedirect() != null) {
        queue.add(node.getRedirect());
      }
    }
    // Write the count
    ProtocolUtils.writeVarInt(buf, idx);
    if (getRedirect() != null) { // New redirect
      flag |= FLAG_IS_REDIRECT;
    }
    buf.writeByte(flag);
    // Write the children
    int[] children = getChildren().stream().mapToInt(newlyAddedNodes::getInt).toArray();
    ProtocolUtils.writeVarInt(buf, children.length + originChildren.length);
    for (int child : originChildren) {
      ProtocolUtils.writeVarInt(buf, child);
    }
    for (int child : children) {
      ProtocolUtils.writeVarInt(buf, child);
    }
    // Write the redirect
    if (getRedirect() != null) {
      ProtocolUtils.writeVarInt(buf, newlyAddedNodes.getInt(getRedirect()));
    } else if (originRedirect.isPresent()) {
      ProtocolUtils.writeVarInt(buf, originRedirect.getAsInt());
    }
    // Write other nodes
    buf.writeBytes(originalData);
    for (CommandNode<CommandSource> node : toEncode) {
      serializeNode(node, buf, newlyAddedNodes, version);
    }
    buf.writeByte(0x00);
  }

  private static void serializeNode(CommandNode<CommandSource> node, ByteBuf buf,
      Object2IntMap<CommandNode<CommandSource>> idMappings, ProtocolVersion protocolVersion) {
    Preconditions.checkArgument(!(node instanceof AppendOnlyRootCommandNode), "Attempt to serialize root node");
    byte flags = 0;
    if (node.getRedirect() != null) {
      flags |= FLAG_IS_REDIRECT;
    }
    if (node.getCommand() != null) {
      flags |= FLAG_EXECUTABLE;
    }

    if (node instanceof LiteralCommandNode<?>) {
      flags |= NODE_TYPE_LITERAL;
    } else if (node instanceof ArgumentCommandNode<?, ?>) {
      flags |= NODE_TYPE_ARGUMENT;
      if (((ArgumentCommandNode<CommandSource, ?>) node).getCustomSuggestions() != null) {
        flags |= FLAG_HAS_SUGGESTIONS;
      }
    }

    buf.writeByte(flags);
    ProtocolUtils.writeVarInt(buf, node.getChildren().size());
    for (CommandNode<CommandSource> child : node.getChildren()) {
      ProtocolUtils.writeVarInt(buf, idMappings.getInt(child));
    }
    if (node.getRedirect() != null) {
      ProtocolUtils.writeVarInt(buf, idMappings.getInt(node.getRedirect()));
    }

    if (node instanceof ArgumentCommandNode<?, ?>) {
      ProtocolUtils.writeString(buf, node.getName());
      ArgumentPropertyRegistry.serialize(buf,
          ((ArgumentCommandNode<CommandSource, ?>) node).getType(), protocolVersion);

      if (((ArgumentCommandNode<CommandSource, ?>) node).getCustomSuggestions() != null) {
        SuggestionProvider<CommandSource> provider = ((ArgumentCommandNode<CommandSource, ?>) node)
            .getCustomSuggestions();
        String name = "minecraft:ask_server";
        if (provider instanceof ProtocolSuggestionProvider) {
          name = ((ProtocolSuggestionProvider) provider).name;
        }
        ProtocolUtils.writeString(buf, name);
      }
    } else if (node instanceof LiteralCommandNode<?>) {
      ProtocolUtils.writeString(buf, node.getName());
    }
  }

  /**
   * A placeholder {@link SuggestionProvider} used internally to preserve the suggestion provider
   * name.
   */
  public static class ProtocolSuggestionProvider implements SuggestionProvider<CommandSource> {

    private final String name;

    public ProtocolSuggestionProvider(String name) {
      this.name = name;
    }

    @Override
    public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSource> context,
        SuggestionsBuilder builder) throws CommandSyntaxException {
      return builder.buildFuture();
    }
  }
}
