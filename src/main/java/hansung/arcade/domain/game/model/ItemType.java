package hansung.arcade.domain.game.model;

/**
 * 아이템 종류. {@link #id}는 클라이언트 {@code hansung.arcade.ingame.item.ItemType}의 id(그림 파일 이름)와 같아야 한다.
 * {@link #symbol}은 맵 파일 [item] 층에 쓰는 한 글자다.
 */
public enum ItemType {
    /** 물풍선 개수 +1 */
    BUBBLE("bubble", 'b'),
    /** 물줄기 길이 +1 */
    FLUID("fluid", 'f'),
    /** 물줄기 길이를 캐릭터 최댓값으로 */
    ULTRA("ultra", 'u'),
    /** 이동속도 +1 */
    ROLLER_SHOE("roller_shoe", 'r'),
    /** 이동속도를 캐릭터 최댓값으로 하고 물풍선을 밀 수 있게 (속도 최대 + 신발) */
    RED_DEVIL("red_devil", 'd'),
    /** 물풍선을 던질 수 있게 */
    GLOVE("glove", 'g'),
    /** 물풍선을 밀 수 있게 */
    SHOE("shoe", 's'),
    /** 물줄기를 한 번 막아 주는 탱크 */
    TANK("tank", 't'),
    /** 갇혔을 때 스스로 빠져나오는 바늘 */
    NEEDLE("needle", 'n'),
    /** 잠깐 물줄기를 막는 보호막 */
    SHIELD("shield", 'h'),
    /** 앞 칸을 뛰어넘는 스프링 신발 */
    SPRING_SHOE("spring_shoe", 'j');

    private final String id;
    private final char symbol;

    ItemType(String id, char symbol) {
        this.id = id;
        this.symbol = symbol;
    }

    public String id() {
        return id;
    }

    public char symbol() {
        return symbol;
    }

    /** 들고 있다가 아이템 키로 쓰는 아이템인지 (바늘·보호막·스프링 신발). 나머지는 먹는 순간 효과가 나는 패시브다 */
    boolean isActive() {
        return switch (this) {
            case NEEDLE, SHIELD, SPRING_SHOE -> true;
            default -> false;
        };
    }

    /** 메시지의 아이템 id(bubble, needle …)에 해당하는 아이템. 없으면 null */
    public static ItemType fromId(String id) {
        for (ItemType item : values()) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    /** 맵 파일 [item] 층 글자에 해당하는 아이템. 없으면 null */
    public static ItemType fromSymbol(char symbol) {
        for (ItemType item : values()) {
            if (item.symbol == symbol) {
                return item;
            }
        }
        return null;
    }
}
