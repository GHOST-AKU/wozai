package dev.ghost.nearbyim.storage;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
public final class SchemaExport {
    public static void main(String[] args) throws Exception {
        if (args.length == 2) {
            for (String statement : StoreSchema.upgradeStatements(Integer.parseInt(args[0]), Integer.parseInt(args[1])))
                System.out.println(Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        System.out.println(StoreSchema.VERSION);
        for (String sql : new String[]{StoreSchema.CREATE_CONVERSATIONS, StoreSchema.CREATE_MESSAGES,
                StoreSchema.CREATE_MESSAGE_INDEX, StoreSchema.CREATE_TRUST, StoreSchema.CONVERSATIONS,
                StoreSchema.REMEMBER_TRUST, StoreSchema.REVOKE_TRUST, StoreSchema.CLEAR_MESSAGES,
                StoreSchema.TOUCH_INSERT, StoreSchema.TOUCH_UPDATE, StoreSchema.RECOVER_PENDING})
            System.out.println(Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8)));
    }
}
