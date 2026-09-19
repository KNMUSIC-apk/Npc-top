package com.example.npctopkill;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Xử lý lệnh /npctopkill.
 * Tất cả lệnh yêu cầu quyền npctopkill.admin.
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    private final NpcTopKill plugin;

    public CommandHandler(NpcTopKill plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("npctopkill.admin")) {
            sender.sendMessage(ChatColor.RED + "Bạn không có quyền sử dụng lệnh này!");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> handleCreate(sender, args);
            case "center" -> handleCenter(sender);
            case "reload" -> handleReload(sender);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Lệnh này chỉ dùng được trong game!");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Cú pháp: /npctopkill create <1|2|3>");
            return;
        }

        int top;
        try {
            top = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Thứ hạng phải là số 1, 2 hoặc 3!");
            return;
        }

        if (top < 1 || top > 3) {
            sender.sendMessage(ChatColor.RED + "Thứ hạng chỉ nhận giá trị 1, 2 hoặc 3!");
            return;
        }

        boolean success = plugin.getNpcManager().createNPC(player, top);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Đã tạo NPC cho Top " + top + " tại vị trí của bạn!");
        } else {
            sender.sendMessage(ChatColor.RED + "Không thể tạo NPC. Vui lòng kiểm tra console!");
        }
    }

    private void handleCenter(CommandSender sender) {
        plugin.getNpcManager().centerAllNPCs();
        sender.sendMessage(ChatColor.GREEN + "Đã dịch chuyển tất cả NPC về giữa block và chỉnh góc nhìn!");
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadPlugin();
        sender.sendMessage(ChatColor.GREEN + "Đã reload config NpcTopKill!");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== NpcTopKill =====");
        sender.sendMessage(ChatColor.YELLOW + "/npctopkill create <1|2|3>" + ChatColor.WHITE + " - Tạo NPC top");
        sender.sendMessage(ChatColor.YELLOW + "/npctopkill center" + ChatColor.WHITE + " - Căn giữa NPC");
        sender.sendMessage(ChatColor.YELLOW + "/npctopkill reload" + ChatColor.WHITE + " - Reload config");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("npctopkill.admin")) return new ArrayList<>();

        if (args.length == 1) {
            return Arrays.asList("create", "center", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return Arrays.asList("1", "2", "3");
        }
        return new ArrayList<>();
    }
}
