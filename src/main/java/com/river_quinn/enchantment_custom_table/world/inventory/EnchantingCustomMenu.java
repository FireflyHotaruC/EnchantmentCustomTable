package com.river_quinn.enchantment_custom_table.world.inventory;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.river_quinn.enchantment_custom_table.Config;
import com.river_quinn.enchantment_custom_table.block.entity.EnchantingCustomTableBlockEntity;
import com.river_quinn.enchantment_custom_table.init.ModMenus;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;

public class EnchantingCustomMenu extends AbstractContainerMenu {
	public static final int ENCHANTED_BOOK_SLOT_ROW_COUNT = 4;
	public static final int ENCHANTED_BOOK_SLOT_COLUMN_COUNT = 6;
	public static final int ENCHANTED_BOOK_SLOT_SIZE = ENCHANTED_BOOK_SLOT_ROW_COUNT * ENCHANTED_BOOK_SLOT_COLUMN_COUNT;
	public static final int ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE = ENCHANTED_BOOK_SLOT_SIZE + 2;
	private final ItemStackHandler itemHandler = new ItemStackHandler(ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE) {
		@Override
		public int getStackLimit(int slot, ItemStack stack) {
			return 1;
		}
	};

	private static final Logger LOGGER = LogUtils.getLogger();
	public final static HashMap<String, Object> guistate = new HashMap<>();
	public final Level world;
	public final Player entity;
	public int x, y, z;
	private final ContainerLevelAccess access;
	private final Map<Integer, Slot> enchantedBookSlots = new HashMap<>();
	public EnchantingCustomTableBlockEntity boundBlockEntity = null;

	public int currentPage = 0;
	public int totalPage = 0;
	private final List<ItemStack> enchantmentsOnCurrentTool = new ArrayList<>();

	public EnchantingCustomMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(ModMenus.ENCHANTING_CUSTOM.get(), id);
		this.entity = inv.player;
		this.world = inv.player.level();

		BlockPos pos = extraData != null ? extraData.readBlockPos() : null;
		if (pos != null) {
			this.x = pos.getX();
			this.y = pos.getY();
			this.z = pos.getZ();
			access = ContainerLevelAccess.create(world, pos);
			boundBlockEntity = (EnchantingCustomTableBlockEntity) world.getBlockEntity(pos);
		} else {
			access = ContainerLevelAccess.NULL;
		}

		// 工具槽
		this.addSlot(new SlotItemHandler(itemHandler, 0, 8, 8) {
			@Override
			public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
				super.setByPlayer(newStack, oldStack);
				if (!newStack.isEmpty()) {
					genEnchantedBookCache();
					currentPage = 0;
					updateEnchantedBookSlots();
				} else {
					clearCache();
					clearPage();
				}
			}
		});

		// 附魔书输入槽
		this.addSlot(new SlotItemHandler(itemHandler, 1, 42, 8) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return Items.ENCHANTED_BOOK == stack.getItem()
					&& !itemHandler.getStackInSlot(0).isEmpty()
					&& (Config.ignoreEnchantmentLevelLimit || checkCanPlaceEnchantedBook(stack));
			}

			@Override
			public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
				return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.fromNamespaceAndPath("enchantment_custom_table", "item/empty_slot_book"));
			}

			@Override
			public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
				super.setByPlayer(newStack, oldStack);
				if (!newStack.isEmpty()) {
					addEnchantment(newStack, 1, true);
				}
			}
		});

		// 附魔书展示槽
		int index = 0;
		for (int row = 0; row < ENCHANTED_BOOK_SLOT_ROW_COUNT; row++) {
			int yPos = 8 + row * 18;
			for (int col = 0; col < ENCHANTED_BOOK_SLOT_COLUMN_COUNT; col++) {
				int xPos = 61 + col * 18;
				int finalIndex = index;
				enchantedBookSlots.put(finalIndex, this.addSlot(
					new SlotItemHandler(itemHandler, finalIndex + 2, xPos, yPos) {
						@Override
						public boolean mayPlace(ItemStack stack) {
							return Items.ENCHANTED_BOOK == stack.getItem()
								&& !itemHandler.getStackInSlot(0).isEmpty()
								&& (Config.ignoreEnchantmentLevelLimit || checkCanPlaceEnchantedBook(stack));
						}

						@Override
						public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
							return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.fromNamespaceAndPath("enchantment_custom_table", "item/empty_slot_book"));
						}
					}
				));
				index++;
			}
		}

		// 玩家背包
		for (int si = 0; si < 3; ++si)
			for (int sj = 0; sj < 9; ++sj)
				this.addSlot(new Slot(inv, sj + (si + 1) * 9, 8 + sj * 18, 84 + si * 18));
		for (int si = 0; si < 9; ++si)
			this.addSlot(new Slot(inv, si, 8 + si * 18, 142));

		initMenu();
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		ItemStack carried = getCarried();
		if (slotId >= 2 && slotId < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE
			&& clickType != ClickType.QUICK_MOVE
			&& (carried.isEmpty() || getSlot(slotId).mayPlace(carried))) {

			ItemStack slotStack = itemHandler.getStackInSlot(slotId);
			boolean hasDuplicate = false;

			if (!carried.isEmpty() && !slotStack.isEmpty()) {
				List<EnchantmentInstance> newEnch = getEnchantmentInstanceFromEnchantedBook(carried);
				List<EnchantmentInstance> oldEnch = getEnchantmentInstanceFromEnchantedBook(slotStack);
				if (!oldEnch.isEmpty()) {
					Holder<Enchantment> old = oldEnch.get(0).enchantment;
					hasDuplicate = newEnch.stream().anyMatch(e -> e.enchantment.equals(old));
				}
			}

			if (hasDuplicate) {
				addEnchantment(carried, slotId, true);
				setCarried(ItemStack.EMPTY);
				return;
			}

			int cacheIndex = (slotId - 2) + currentPage * ENCHANTED_BOOK_SLOT_SIZE;
			if (!slotStack.isEmpty()) {
				setCarried(slotStack.copy());
				boolean hasRegenerated = removeEnchantment(slotStack);
				if (!hasRegenerated && cacheIndex >= 0 && cacheIndex < enchantmentsOnCurrentTool.size()) {
					enchantmentsOnCurrentTool.set(cacheIndex, ItemStack.EMPTY);
				}
			} else {
				setCarried(ItemStack.EMPTY);
			}

			if (!carried.isEmpty()) {
				addEnchantment(carried, slotId);
			}
			updateEnchantedBookSlots();
			return;
		}
		super.clicked(slotId, button, clickType, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = this.slots.get(index);
		if (!slot.hasItem()) return ItemStack.EMPTY;

		ItemStack itemStackToOperate = slot.getItem().copy();
		ItemStack stack = slot.getItem().copy();
		ItemStack result = stack.copy();

		if (index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE) {
			if (index > 1 && index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE && !itemStackToOperate.isEmpty()) {
				syncCurrentPageToCache();
				int cacheIndex = (index - 2) + currentPage * ENCHANTED_BOOK_SLOT_SIZE;
				if (cacheIndex >= 0 && cacheIndex < enchantmentsOnCurrentTool.size()) {
					enchantmentsOnCurrentTool.set(cacheIndex, ItemStack.EMPTY);
				}
				removeEnchantment(itemStackToOperate);
			}
			if (!this.moveItemStackTo(stack, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, this.slots.size(), true)) {
				return ItemStack.EMPTY;
			}
			if (index == 0) {
				clearCache();
				clearPage();
			}
		} else {
			if (!this.moveItemStackTo(stack, 0, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, false)) {
				return ItemStack.EMPTY;
			}
		}

		if (stack.isEmpty()) {
			slot.set(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		updateEnchantedBookSlots();
		return result;
	}

	@Override
	protected boolean moveItemStackTo(ItemStack stack, int start, int end, boolean reverse) {
		boolean merged = false;
		int i = reverse ? end - 1 : start;

		if (stack.isStackable()) {
			while (!stack.isEmpty() && (reverse ? i >= start : i < end)) {
				Slot slot = slots.get(i);
				ItemStack slotStack = slot.getItem();
				if (!slotStack.isEmpty() && ItemStack.isSameItemSameComponents(stack, slotStack)) {
					int max = Math.min(slot.getMaxStackSize(), stack.getMaxStackSize());
					int sum = slotStack.getCount() + stack.getCount();
					if (sum <= max) {
						stack.setCount(0);
						slotStack.setCount(sum);
						slot.setChanged();
						merged = true;
					} else if (slotStack.getCount() < max) {
						stack.shrink(max - slotStack.getCount());
						slotStack.setCount(max);
						slot.setChanged();
						merged = true;
					}
				}
				i += reverse ? -1 : 1;
			}
		}

		if (!stack.isEmpty()) {
			i = reverse ? end - 1 : start;
			while (reverse ? i >= start : i < end) {
				Slot slot = slots.get(i);
				if (slot.getItem().isEmpty() && slot.mayPlace(stack)) {
					int max = slot.getMaxStackSize(stack);
					slot.setByPlayer(stack.split(Math.min(stack.getCount(), max)));
					slot.setChanged();
					merged = true;
					break;
				}
				i += reverse ? -1 : 1;
			}
		}
		return merged;
	}

	// 从附魔书获取附魔实例
	public List<EnchantmentInstance> getEnchantmentInstanceFromEnchantedBook(ItemStack book) {
		List<EnchantmentInstance> list = new ArrayList<>();
		ItemEnchantments ench = book.getOrDefault(EnchantmentHelper.getComponentType(book), ItemEnchantments.EMPTY);
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : ench.entrySet()) {
				list.add(new EnchantmentInstance(entry.getKey(), entry.getIntValue()));
			}
		return list;
	}

	// 检查附魔书是否可以放置（等级不超限）
	public boolean checkCanPlaceEnchantedBook(ItemStack stack) {
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty()) return false;

		ItemEnchantments bookEnch = stack.getOrDefault(EnchantmentHelper.getComponentType(stack), ItemEnchantments.EMPTY);
		ItemEnchantments toolEnch = tool.getOrDefault(EnchantmentHelper.getComponentType(tool), ItemEnchantments.EMPTY);

		for (var entry : bookEnch.entrySet()) {
			Holder<Enchantment> e = entry.getKey();
			int add = entry.getIntValue();
			int has = toolEnch.getLevel(e);
			if (has + add > e.value().getMaxLevel()) {
				return false;
			}
		}
		return true;
	}

	// 导出所有附魔，现在导出附魔书不会有操作
	public void exportAllEnchantments() {
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.is(Items.ENCHANTED_BOOK)) {
			return;
		}

		ItemEnchantments ench = tool.getOrDefault(EnchantmentHelper.getComponentType(tool), ItemEnchantments.EMPTY);
		if (tool.isEmpty() || ench.isEmpty()) return;

		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ench);
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);

		for (var entry : ench.entrySet()) {
			Holder<Enchantment> e = entry.getKey();
			int lvl = entry.getIntValue();
			mutable.set(e, 0);
			book.enchant(e, lvl);
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		entity.getInventory().placeItemBackInInventory(book);
		playSound();
		clearCache();
	}

	// 翻页逻辑
	public void nextPage() {
		if (currentPage < totalPage - 1) turnPage(currentPage + 1);
	}

	public void previousPage() {
		if (currentPage > 0) turnPage(currentPage - 1);
	}

	public void turnPage(int target) {
		if (target < 0 || target >= totalPage) return;
		syncCurrentPageToCache();
		currentPage = target;
		updateEnchantedBookSlots();
	}

	// 更新附魔书槽显示
	public void updateEnchantedBookSlots() {
		itemHandler.setStackInSlot(1, ItemStack.EMPTY);
		int offset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;
		for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
			int idx = offset + i;
			ItemStack stack = ItemStack.EMPTY;
			if (idx < enchantmentsOnCurrentTool.size()) {
				ItemStack cached = enchantmentsOnCurrentTool.get(idx);
				if (cached != null && !cached.isEmpty()) stack = cached.copy();
			}
			itemHandler.setStackInSlot(i + 2, stack);
		}
		broadcastChanges();
	}

	// 清空缓存
	public void clearCache() {
		for (int i = 2; i < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE; i++) {
			itemHandler.setStackInSlot(i, ItemStack.EMPTY);
		}
		genEnchantedBookCache();
	}

	// 清空页码
	public void clearPage() {
		currentPage = totalPage = 0;
	}

	// 生成附魔书缓存
	public void genEnchantedBookCache() {
		ItemStack tool = itemHandler.getStackInSlot(0);
		enchantmentsOnCurrentTool.clear();
		totalPage = 0;

		if (tool.isEmpty()) return;

		ItemEnchantments ench = tool.getOrDefault(EnchantmentHelper.getComponentType(tool), ItemEnchantments.EMPTY);

		if (tool.is(Items.ENCHANTED_BOOK) && ench.entrySet().size() == 1) {
			var entry = ench.entrySet().iterator().next();
			int lvl = entry.getIntValue();
			if (lvl > 1) {
				List<Integer> levels = new ArrayList<>();
				while (lvl > 0) {
					int add = (lvl + 1) / 2;
					levels.add(add);
					lvl -= add;
				}
				for (int l : levels) {
					ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
					b.enchant(entry.getKey(), l);
					enchantmentsOnCurrentTool.add(b);
				}
			}
		} else {
			for (var entry : ench.entrySet()) {
				ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
				b.enchant(entry.getKey(), entry.getIntValue());
				enchantmentsOnCurrentTool.add(b);
			}
		}

		totalPage = Math.max(1, (enchantmentsOnCurrentTool.size() + ENCHANTED_BOOK_SLOT_SIZE - 1) / ENCHANTED_BOOK_SLOT_SIZE);
		while (enchantmentsOnCurrentTool.size() < totalPage * ENCHANTED_BOOK_SLOT_SIZE) {
			enchantmentsOnCurrentTool.add(ItemStack.EMPTY);
		}
	}

	// 添加附魔
	public void addEnchantment(ItemStack stack, int slot) {
		addEnchantment(stack, slot, false);
	}

	public void addEnchantment(ItemStack stack, int slot, boolean forceRegen) {
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty() || stack.isEmpty()) return;

		List<EnchantmentInstance> bookEnch = getEnchantmentInstanceFromEnchantedBook(stack);
		ItemEnchantments toolEnch = tool.getOrDefault(EnchantmentHelper.getComponentType(tool), ItemEnchantments.EMPTY);
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(toolEnch);

		for (EnchantmentInstance inst : bookEnch) {
			int current = mutable.getLevel(inst.enchantment);
			mutable.set(inst.enchantment, current + inst.level);
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		genEnchantedBookCache();
		updateEnchantedBookSlots();
		playSound();
	}

	// 移除附魔
	public boolean removeEnchantment(ItemStack stack) {
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty() || stack.isEmpty()) return false;

		List<EnchantmentInstance> removeList = getEnchantmentInstanceFromEnchantedBook(stack);
		ItemEnchantments toolEnch = tool.getOrDefault(EnchantmentHelper.getComponentType(tool), ItemEnchantments.EMPTY);

		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(toolEnch);
		for (EnchantmentInstance inst : removeList) {
			int current = mutable.getLevel(inst.enchantment);
			mutable.set(inst.enchantment, Math.max(0, current - inst.level));
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		int newTotalPage = Math.max((int) Math.ceil((double) mutable.keySet().size() / ENCHANTED_BOOK_SLOT_SIZE), 1);
		boolean hasRegenerated = tool.is(Items.ENCHANTED_BOOK) && mutable.keySet().size() == 1
			|| totalPage != newTotalPage;
		if (hasRegenerated) {
			genEnchantedBookCache();
			currentPage = Math.min(currentPage, totalPage - 1);
		}
		playSound();
		return hasRegenerated;
	}

	// 初始化菜单
	public void initMenu() {
		clearPage();
		clearCache();
	}

	// 播放音效
	private void playSound() {
		if (!world.isClientSide && boundBlockEntity != null) {
			world.playSound(null, boundBlockEntity.getBlockPos(),
							SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
	}

	// 同步当前页显示内容到总缓存
	private void syncCurrentPageToCache() {
		int offset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;
		for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
			int fullIndex = offset + i;
			if (fullIndex >= 0 && fullIndex < enchantmentsOnCurrentTool.size()) {
				enchantmentsOnCurrentTool.set(fullIndex, itemHandler.getStackInSlot(i + 2).copy());
			}
		}
	}

	// 菜单有效性检查
	@Override
	public boolean stillValid(Player player) {
		if (boundBlockEntity == null || boundBlockEntity.isRemoved())
			return false;
		return stillValid(access, player, boundBlockEntity.getBlockState().getBlock());
	}

	// 关闭菜单时返还物品
	@Override
	public void removed(@NotNull Player player) {
		super.removed(player);
		if (player instanceof ServerPlayer serverPlayer) {
			for (int i = 0; i < 2; i++) {
				ItemStack stack = itemHandler.getStackInSlot(i);
				if (!stack.isEmpty()) {
					serverPlayer.getInventory().placeItemBackInInventory(stack);
					itemHandler.setStackInSlot(i, ItemStack.EMPTY);
				}
			}
		}
	}
}
