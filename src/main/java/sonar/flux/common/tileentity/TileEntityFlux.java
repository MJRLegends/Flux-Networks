package sonar.flux.common.tileentity;

import java.util.Arrays;
import java.util.UUID;

import cofh.api.energy.IEnergyConnection;
import cofh.api.energy.IEnergyHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import sonar.core.SonarCore;
import sonar.core.api.SonarAPI;
import sonar.core.common.tileentity.TileEntitySonar;
import sonar.core.helpers.SonarHelper;
import sonar.core.network.sync.SyncTagType;
import sonar.core.network.sync.SyncTagType.INT;
import sonar.core.network.sync.SyncTagType.LONG;
import sonar.core.network.sync.SyncTagType.STRING;
import sonar.core.network.sync.SyncUUID;
import sonar.core.network.utils.IByteBufTile;
import sonar.flux.FluxNetworks;
import sonar.flux.api.FluxError;
import sonar.flux.api.IFlux;
import sonar.flux.api.IFluxCommon;
import sonar.flux.api.IFluxController;
import sonar.flux.api.IFluxNetwork;
import sonar.flux.network.CommonNetworkCache;
import sonar.flux.network.CommonNetworkCache.ViewingType;
import sonar.flux.network.PacketFluxError;
import sonar.flux.network.ServerNetworkCache;

public abstract class TileEntityFlux extends TileEntitySonar implements IFlux, IEnergyHandler, IByteBufTile {
	// shared
	public SyncTagType.INT priority = new SyncTagType.INT(0);
	public SyncTagType.LONG limit = (LONG) new SyncTagType.LONG(1).setDefault(Long.valueOf(256000));
	//public SyncTagType.STRING networkName = new SyncTagType.STRING(2);
	//public SyncTagType.STRING networkOwner = new SyncTagType.STRING(3);
	public SyncTagType.INT networkID = (INT) new SyncTagType.INT(4).setDefault(-1);
	//public SyncTagType.STRING playerName = new SyncTagType.STRING(5);
	public SyncUUID playerUUID = new SyncUUID(5);
	public SyncTagType.STRING customName = (STRING) new SyncTagType.STRING(6).setDefault("Flux Connection");
	public SyncTagType.INT colour = new SyncTagType.INT(7);
	public boolean[] connections = new boolean[6];
	private final ConnectionType type;

	// server only
	private IFluxNetwork network = ServerNetworkCache.empty;
	private int checkTicks = 0;

	// client only
	public FluxError error = FluxError.NONE;

	public TileEntityFlux(ConnectionType type) {
		super();
		this.type = type;
		syncParts.addAll(Arrays.asList(priority, limit, networkID, playerUUID, customName, colour));
	}

	public void setPlayerUUID(UUID name) {
		this.playerUUID.setObject(name);
	}

	public void changeNetwork(IFluxNetwork network) {
		changeNetwork(network, null);
	}

	public void changeNetwork(IFluxNetwork network, EntityPlayer player) {
		if (isServer()) {
			this.network = network;
			if (player != null && !network.isFakeNetwork() && this instanceof IFluxController) {
				if (network.hasController()) {
					FluxNetworks.network.sendTo(new PacketFluxError(getPos(), FluxError.HAS_CONTROLLER), (EntityPlayerMP) player);
					changeNetwork(CommonNetworkCache.empty, null);
					return;
				}
			}
			network.addFluxConnection(this);
			networkID.setObject(network.getNetworkID());
			colour.setObject(network.getNetworkColour().getRGB());
			markDirty();
			markBlockForUpdate();
		}
	}

	public void update() {
		super.update();
		if (isServer()) {
			if (checkTicks >= 20) {
				updateConnections();
				checkTicks=0;
			} else {
				checkTicks++;
			}
		}
	}

	@Override
	public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
		super.onDataPacket(net, packet);
		SonarCore.sendPacketAround(this, 128, 0);

	}

	public void updateConnections() {
		for (EnumFacing face : EnumFacing.VALUES) {
			BlockPos pos = this.pos.offset(face);
			TileEntity tile = getWorld().getTileEntity(pos);
			if (tile != null && !(tile instanceof IFlux)) {
				if (tile instanceof IEnergyConnection) {
					connections[face.getIndex()] = true;
					continue;
				}
				if (SonarAPI.getEnergyHelper().canTransferEnergy(tile, face) != null) {
					connections[face.getIndex()] = true;
				}
			} else {
				connections[face.getIndex()] = false;
			}
		}
		SonarCore.sendPacketAround(this, 128, 0);
	}

	public void onFirstTick() {
		super.onFirstTick();
		if (playerUUID != null && isServer() && networkID.getObject()!=-1) {			
			IFluxCommon network = FluxNetworks.cache.getNetwork(networkID.getObject());
			if (!network.isFakeNetwork() && network instanceof IFluxNetwork) {
				changeNetwork((IFluxNetwork) network, null);
			}
		}
	}

	public void invalidate() {
		super.invalidate();
		if (playerUUID != null && isServer()) {
			IFluxCommon network = FluxNetworks.cache.getNetwork(networkID.getObject());			
			if (!network.isFakeNetwork() && network instanceof IFluxNetwork) {
				((IFluxNetwork) network).removeFluxConnection(this);
			}
		}
	}

	@Override
	public IFluxNetwork getNetwork() {
		return network;
	}

	@Override
	public ConnectionType getConnectionType() {
		return type;
	}

	@Override
	public long getTransferLimit() {
		return limit.getObject();
	}

	@Override
	public int getCurrentPriority() {
		return priority.getObject();
	}

	@Override
	public World getDimension() {
		return worldObj;
	}

	@Override
	public String getCustomName() {
		return customName.getObject();
	}

	@Override
	public boolean canConnectEnergy(EnumFacing from) {
		return true;
	}

	@Override
	public int getEnergyStored(EnumFacing from) {
		return 0;
	}

	@Override
	public int getMaxEnergyStored(EnumFacing from) {
		return Integer.MAX_VALUE;
	}

	@Override
	public void writePacket(ByteBuf buf, int id) {
		switch (id) {
		case 0:
			for (int i = 0; i < 6; i++) {
				buf.writeBoolean(connections[i]);
			}
			break;
		case 1:
			priority.writeToBuf(buf);
			break;
		case 2:
			limit.writeToBuf(buf);
			break;
		case 3:
			customName.writeToBuf(buf);
			break;
		}
	}

	@Override
	public void readPacket(ByteBuf buf, int id) {
		switch (id) {
		case 0:
			for (int i = 0; i < 6; i++) {
				connections[i] = buf.readBoolean();
			}
			markBlockForUpdate();
			break;
		case 1:
			priority.readFromBuf(buf);
			break;
		case 2:
			limit.readFromBuf(buf);
			break;
		case 3:
			customName.readFromBuf(buf);
			break;
		case 4:
			EntityPlayer player = SonarHelper.getPlayerFromUUID(playerUUID.getUUID());
			if (player != null)
				FluxNetworks.cache.addViewer(player, ViewingType.CONNECTIONS, networkID.getObject());
			break;
		case 5:
			player = SonarHelper.getPlayerFromUUID(playerUUID.getUUID());
			if (player != null) {
				FluxNetworks.cache.removeViewer(player);
				FluxNetworks.cache.addViewer(player, ViewingType.CLIENT, networkID.getObject());
			}
			break;
		}
	}
}
