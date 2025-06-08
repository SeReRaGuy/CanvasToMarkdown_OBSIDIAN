import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.*;

public class CanvasParser {

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Node {
        public String id;
        public String type;
        public String label;      // для group: label
        public String text;       // для text
        public String file;       // для file
        public int x, y, width, height;

        @Override
        public String toString() {
            if ("group".equals(type)) return "Group[id=" + id + ", '" + label + "']";
            else if ("text".equals(type)) return "Text[id=" + id + ", firstLine=" + firstLine(text) + "]";
            else if ("file".equals(type)) return "File[id=" + id + ", path=" + file + "]";
            else return type + "[id=" + id + "]";
        }

        private String firstLine(String t) {
            if (t == null) return "";
            int nl = t.indexOf('\n');
            return nl != -1 ? t.substring(0, nl) : t;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Edge {
        public String fromNode;
        public String toNode;
    }

    static class Canvas {
        public List<Node> nodes;
        public List<Edge> edges;
    }

    // Вспомогательный класс для построения дерева групп с вложениями
    static class GroupNode {
        Node group;
        List<Object> children = new ArrayList<>(); // может содержать Node или GroupNode

        GroupNode(Node group) {
            this.group = group;
        }
    }

    public static void main(String[] args) throws Exception {
        String canvasPath = "E:/Хранение/Синхронизация с гугл диском/Obsidian хранилища/Хранилище/Спорт/Спортивный план.canvas";
        File canvasFile = new File(canvasPath);

        ObjectMapper mapper = new ObjectMapper();
        Canvas canvas = mapper.readValue(canvasFile, Canvas.class);

        // Разделяем группы и остальные узлы
        Map<String, Node> allNodesById = new HashMap<>();
        List<Node> groups = new ArrayList<>();
        List<Node> others = new ArrayList<>();

        for (Node n : canvas.nodes) {
            allNodesById.put(n.id, n);
            if ("group".equals(n.type)) groups.add(n);
            else others.add(n);
        }

        // Создаём объекты GroupNode для каждой группы
        Map<String, GroupNode> groupNodes = new HashMap<>();
        for (Node g : groups) {
            groupNodes.put(g.id, new GroupNode(g));
        }

        // Для каждой группы определяем вложенные группы (вложенные группы находятся внутри прямоугольника родительской)
        for (Node g : groups) {
            GroupNode parentGroupNode = groupNodes.get(g.id);
            for (Node possibleChildGroup : groups) {
                if (g == possibleChildGroup) continue;
                if (isInsideGroup(possibleChildGroup, g)) {
                    // Добавляем вложенную группу к родителю, если она не вложена в другую группу внутри g
                    boolean isNestedInOtherGroup = false;
                    for (Node otherGroup : groups) {
                        if (otherGroup == g || otherGroup == possibleChildGroup) continue;
                        if (isInsideGroup(possibleChildGroup, otherGroup) && isInsideGroup(otherGroup, g)) {
                            isNestedInOtherGroup = true;
                            break;
                        }
                    }
                    if (!isNestedInOtherGroup) {
                        parentGroupNode.children.add(groupNodes.get(possibleChildGroup.id));
                    }
                }
            }
        }

        // Добавляем обычные узлы в соответствующие группы (учитывая вложенность)
        Set<Node> assignedNodes = new HashSet<>();
        for (Node n : others) {
            GroupNode containerGroup = findDeepestContainingGroup(n, groups, groupNodes);
            if (containerGroup != null) {
                containerGroup.children.add(n);
                assignedNodes.add(n);
            }
        }

        // Выводим группы без родителей - корневые группы
        List<GroupNode> roots = new ArrayList<>();
        for (GroupNode gNode : groupNodes.values()) {
            boolean hasParent = false;
            for (GroupNode possibleParent : groupNodes.values()) {
                if (possibleParent == gNode) continue;
                if (isInsideGroup(gNode.group, possibleParent.group)) {
                    hasParent = true;
                    break;
                }
            }
            if (!hasParent) roots.add(gNode);
        }

        // Также выводим узлы, которые не попали в группы
        List<Node> ungroupedNodes = new ArrayList<>();
        for (Node n : others) {
            if (!assignedNodes.contains(n)) {
                ungroupedNodes.add(n);
            }
        }

        // Формируем Markdown
        StringBuilder mdOutput = new StringBuilder();

        // Сначала корневые группы с вложениями
        for (GroupNode root : roots) {
            buildMarkdown(root, 0, mdOutput);
        }

        // Потом узлы без групп (если есть)
        if (!ungroupedNodes.isEmpty()) {
            mdOutput.append("# Без группы\n\n");
            for (Node n : ungroupedNodes) {
                mdOutput.append("- ").append(nodeDescription(n)).append("\n");
            }
            mdOutput.append("\n");
        }

        // Печать результата
        System.out.println(mdOutput.toString());
    }

    // Возвращает описание узла для Markdown
    private static String nodeDescription(Node n) {
        switch (n.type) {
            case "text":
                return n.firstLine(n.text);
            case "file":
                return "File: " + n.file;
            case "group":
                return "Group: " + (n.label != null ? n.label : "");
            default:
                return n.type;
        }
    }

    // Рекурсивное построение Markdown для группы и её детей
    private static void buildMarkdown(GroupNode g, int level, StringBuilder md) {
        int headerLevel = Math.min(level + 2, 6); // ограничим max заголовок ######
        String hashes = "#".repeat(headerLevel);
        md.append(hashes).append(" ").append(g.group.label != null ? g.group.label : "Group").append("\n\n");

        for (Object ch : g.children) {
            if (ch instanceof GroupNode) {
                buildMarkdown((GroupNode) ch, level + 1, md);
            } else if (ch instanceof Node) {
                Node n = (Node) ch;
                String prefix = "  ".repeat(level) + "- ";
                md.append(prefix).append(nodeDescription(n)).append("\n");
            }
        }
        md.append("\n");
    }

    // Проверка, что узел n находится внутри группы g
    private static boolean isInsideGroup(Node n, Node g) {
        return n.x >= g.x &&
                n.x <= g.x + g.width &&
                n.y >= g.y &&
                n.y <= g.y + g.height;
    }

    // Находит самую вложенную группу, содержащую узел n (если есть)
    private static GroupNode findDeepestContainingGroup(Node n, List<Node> groups, Map<String, GroupNode> groupNodes) {
        GroupNode deepest = null;
        int deepestArea = Integer.MAX_VALUE; // чтобы выбрать минимальную площадь группы, наиболее точно "вложенную"
        for (Node g : groups) {
            if (isInsideGroup(n, g)) {
                int area = g.width * g.height;
                if (area < deepestArea) {
                    deepest = groupNodes.get(g.id);
                    deepestArea = area;
                }
            }
        }
        return deepest;
    }
}
