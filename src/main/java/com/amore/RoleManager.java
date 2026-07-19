package com.amore;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RoleManager {
    public static void start(JDA jda) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                DatabaseManager db = DatabaseManager.getInstance();
                long now = System.currentTimeMillis();
                List<DatabaseManager.ExpiredRole> expiredRoles = db.getExpiredRoles(now);
                
                if (expiredRoles.isEmpty()) return;

                for (Guild guild : jda.getGuilds()) {
                    for (DatabaseManager.ExpiredRole expired : expiredRoles) {
                        Role role = guild.getRoleById(expired.roleId);
                        if (role != null) {
                            guild.retrieveMemberById(expired.userId).queue(member -> {
                                guild.removeRoleFromMember(member, role).queue(
                                    success -> db.deleteRoleTimer(expired.userId, expired.roleId),
                                    error -> db.deleteRoleTimer(expired.userId, expired.roleId)
                                );
                            }, error -> {
                                db.deleteRoleTimer(expired.userId, expired.roleId);
                            });
                        } else {
                            db.deleteRoleTimer(expired.userId, expired.roleId);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 1, 5, TimeUnit.MINUTES);
    }
}