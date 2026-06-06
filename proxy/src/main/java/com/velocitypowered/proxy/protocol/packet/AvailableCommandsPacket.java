/*
 * Copyright (C) 2018-2023 Velocity Contributors
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

package com.velocitypowered.proxy.protocol.packet;

import com.google.common.base.Preconditions;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.ProtocolUtils.Direction;
import com.velocitypowered.proxy.protocol.packet.brigadier.AppendOnlyRootCommandNode;
import io.netty.buffer.ByteBuf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jetbrains.annotations.NotNull;

public class AvailableCommandsPacket implements MinecraftPacket {
  private @MonotonicNonNull AppendOnlyRootCommandNode root;

  @Override
  public void decode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    root = AppendOnlyRootCommandNode.decodePacket(buf);
  }

  @Override
  public void encode(ByteBuf buf, Direction direction, ProtocolVersion protocolVersion) {
    root.encodePacket(buf, protocolVersion);
  }

  @Override
  public boolean handle(MinecraftSessionHandler handler) {
    return handler.handle(this);
  }

  public @NotNull AppendOnlyRootCommandNode getRootNode() {
    Preconditions.checkNotNull(root, "root");
    return root;
  }

  @Override
  public int encodeSizeHint(Direction direction, ProtocolVersion version) {
    // This is a very complex packet to encode. Paper 1.21.10 + Velocity with Spark has a size of
    // 30,334, but this is likely on the lower side. We'll use 128KiB as a more realistically-sized
    // amount.
    return 128 * 1024;
  }
}
