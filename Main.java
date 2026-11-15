import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        String dir = "data";
        if (args.length > 0) {
            dir = args[0];
        }
        Database db = new Database(dir);
        Scanner scanner = new Scanner(System.in, "UTF-8");
        System.out.println("MiniDB. Type 'help' for commands.");

        String query = "";
        while (true) {
            System.out.print(query.isEmpty() ? "minidb> " : "     -> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine().trim();
            if (query.isEmpty()) {
                if (line.isEmpty()) {
                    continue;
                } else if (line.equalsIgnoreCase("exit")) {
                    break;
                } else if (line.equalsIgnoreCase("help")) {
                    printHelp();
                    continue;
                } else if (line.equalsIgnoreCase("tables")) {
                    System.out.print(db.describeTables());
                    continue;
                }
            }
            query = query + " " + line;
            if (line.endsWith(";")) {
                for (String q : splitQueries(query)) {
                    try {
                        System.out.println(db.execute(q));
                    } catch (DbException e) {
                        System.out.println("Error: " + e.getMessage());
                    }
                }
                query = "";
            }
        }
    }

    private static List<String> splitQueries(String text) {
        List<String> queries = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inQuotes = !inQuotes;
            }
            if (c == ';' && !inQuotes) {
                if (current.toString().trim().length() > 0) {
                    queries.add(current.toString());
                }
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.toString().trim().length() > 0) {
            queries.add(current.toString());
        }
        return queries;
    }

    private static void printHelp() {
        System.out.println("CREATE TABLE t (id INT PRIMARY KEY, name STRING, owner_id INT REFERENCES owners);");
        System.out.println("DROP TABLE t;");
        System.out.println("CREATE INDEX idx_name ON t (name);");
        System.out.println("INSERT INTO t VALUES (1, 'a'), (2, 'b');");
        System.out.println("INSERT INTO t (id, name) VALUES (3, 'c');");
        System.out.println("SELECT * FROM t WHERE id > 1 AND name != 'b';");
        System.out.println("UPDATE t SET name = 'x' WHERE id = 2;");
        System.out.println("DELETE FROM t WHERE id = 1;");
        System.out.println("Types: INT, DOUBLE, STRING, DATE ('YYYY-MM-DD'), BOOLEAN");
        System.out.println("Other commands: tables, help, exit");
    }
}

class Database {
    private static final List<String> TYPES = Arrays.asList("INT", "DOUBLE", "STRING", "DATE", "BOOLEAN");
    private static final List<String> OPERATORS = Arrays.asList("=", "!=", "<", "<=", ">", ">=");

    private final TableRepository repository;
    private final Map<String, Table> tables;
    private boolean autoSave = true;

    private List<String> tokens;
    private int pos;

    public Database(String dir) {
        repository = new TableRepository(dir);
        tables = repository.loadAll();
    }

    public void setAutoSave(boolean autoSave) {
        this.autoSave = autoSave;
    }

    public String execute(String sql) {
        tokens = tokenize(sql);
        pos = 0;
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).equals(";")) {
            tokens.remove(tokens.size() - 1);
        }
        if (tokens.isEmpty()) {
            return "";
        }
        String command = next().toUpperCase();
        String result;
        if (command.equals("CREATE")) {
            if (accept("TABLE")) {
                result = createTable();
            } else if (accept("INDEX")) {
                result = createIndex();
            } else {
                throw new DbException("Expected TABLE or INDEX after CREATE");
            }
        } else if (command.equals("DROP")) {
            result = dropTable();
        } else if (command.equals("INSERT")) {
            result = insert();
        } else if (command.equals("SELECT")) {
            result = select();
        } else if (command.equals("UPDATE")) {
            result = update();
        } else if (command.equals("DELETE")) {
            result = delete();
        } else {
            throw new DbException("Unknown command: " + command);
        }
        if (pos < tokens.size()) {
            throw new DbException("Unexpected: " + tokens.get(pos));
        }
        return result;
    }

    public String describeTables() {
        StringBuilder sb = new StringBuilder();
        for (Table t : tables.values()) {
            sb.append(t.getName()).append(" (");
            for (int i = 0; i < t.getColumns().size(); i++) {
                Column c = t.getColumns().get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(c.name).append(" ").append(c.type);
                if (c.primaryKey) {
                    sb.append(" PK");
                }
                if (c.refTable != null) {
                    sb.append(" -> ").append(c.refTable);
                }
            }
            sb.append("), rows: ").append(t.size()).append("\n");
        }
        return sb.toString();
    }


    private String createTable() {
        String name = readName();
        if (tables.containsKey(name)) {
            throw new DbException("Table already exists: " + name);
        }
        List<Column> columns = new ArrayList<>();
        expect("(");
        do {
            String columnName = readName();
            String type = next().toUpperCase();
            if (!TYPES.contains(type)) {
                throw new DbException("Unknown type: " + type);
            }
            boolean primaryKey = false;
            String refTable = null;
            while (true) {
                if (accept("PRIMARY")) {
                    expect("KEY");
                    primaryKey = true;
                } else if (accept("REFERENCES")) {
                    refTable = readName();
                    Table parent = getTable(refTable);
                    if (!parent.getColumns().get(parent.getPkPos()).type.equals(type)) {
                        throw new DbException("Type of " + columnName + " doesn't match key of " + refTable);
                    }
                } else {
                    break;
                }
            }
            columns.add(new Column(columnName, type, primaryKey, refTable));
        } while (accept(","));
        expect(")");

        Table table = new Table(name, columns);
        tables.put(name, table);
        save(table);
        return "Table " + name + " created";
    }

    private String dropTable() {
        expect("TABLE");
        Table table = getTable(readName());
        for (Table other : tables.values()) {
            for (Column c : other.getColumns()) {
                if (other != table && table.getName().equals(c.refTable)) {
                    throw new DbException("Table is used by " + other.getName() + "." + c.name);
                }
            }
        }
        tables.remove(table.getName());
        repository.delete(table.getName());
        return "Table " + table.getName() + " dropped";
    }

    private String createIndex() {
        String indexName = readName();
        expect("ON");
        Table table = getTable(readName());
        expect("(");
        String column = readName();
        expect(")");
        table.createIndex(indexName, column);
        save(table);
        return "Index " + indexName + " created";
    }

    private String insert() {
        expect("INTO");
        Table table = getTable(readName());
        List<Column> columns = table.getColumns();

        List<Integer> positions = new ArrayList<>();
        if (accept("(")) {
            do {
                positions.add(table.columnIndex(readName()));
            } while (accept(","));
            expect(")");
        } else {
            for (int i = 0; i < columns.size(); i++) {
                positions.add(i);
            }
        }

        expect("VALUES");
        List<Object[]> added = new ArrayList<>();
        try {
            do {
                expect("(");
                Object[] row = new Object[columns.size()];
                int k = 0;
                do {
                    if (k >= positions.size()) {
                        throw new DbException("Too many values");
                    }
                    int p = positions.get(k);
                    row[p] = convert(columns.get(p).type, next());
                    k++;
                } while (accept(","));
                expect(")");
                if (k < positions.size()) {
                    throw new DbException("Too few values");
                }
                for (int i = 0; i < columns.size(); i++) {
                    checkReference(columns.get(i), row[i]);
                }
                table.insert(row);
                added.add(row);
            } while (accept(","));
        } catch (DbException e) {
            for (Object[] row : added) {
                table.delete(row);
            }
            throw e;
        }
        save(table);
        return added.size() + " row(s) inserted";
    }

    private String select() {
        List<String> names = new ArrayList<>();
        if (!accept("*")) {
            do {
                names.add(readName());
            } while (accept(","));
        }
        expect("FROM");
        Table table = getTable(readName());
        Where where = readWhere(table);

        List<Integer> cols = new ArrayList<>();
        if (names.isEmpty()) {
            for (int i = 0; i < table.getColumns().size(); i++) {
                cols.add(i);
                names.add(table.getColumns().get(i).name);
            }
        } else {
            for (String n : names) {
                cols.add(table.columnIndex(n));
            }
        }

        List<Object[]> found = table.find(where);
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : found) {
            Object[] out = new Object[cols.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = row[cols.get(i)];
            }
            result.add(out);
        }
        return makeTable(names, result) + result.size() + " row(s)";
    }

    private String update() {
        Table table = getTable(readName());
        expect("SET");
        List<Integer> positions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        do {
            int p = table.columnIndex(readName());
            expect("=");
            if (p == table.getPkPos()) {
                throw new DbException("Primary key can't be updated");
            }
            Column column = table.getColumns().get(p);
            Object value = convert(column.type, next());
            checkReference(column, value);
            positions.add(p);
            values.add(value);
        } while (accept(","));
        Where where = readWhere(table);

        List<Object[]> found = table.find(where);
        for (Object[] row : found) {
            for (int i = 0; i < positions.size(); i++) {
                table.update(row, positions.get(i), values.get(i));
            }
        }
        save(table);
        return found.size() + " row(s) updated";
    }

    private String delete() {
        expect("FROM");
        Table table = getTable(readName());
        Where where = readWhere(table);

        List<Object[]> found = table.find(where);
        for (Object[] row : found) {
            if (isReferenced(table, row[table.getPkPos()])) {
                throw new DbException("Can't delete " + row[table.getPkPos()] + ": it is used in another table");
            }
        }
        for (Object[] row : found) {
            table.delete(row);
        }
        save(table);
        return found.size() + " row(s) deleted";
    }

    private void checkReference(Column column, Object value) {
        if (column.refTable != null && value != null) {
            Table parent = getTable(column.refTable);
            if (parent.findByPk(value) == null) {
                throw new DbException("No row with key " + value + " in table " + column.refTable);
            }
        }
    }

    private boolean isReferenced(Table parent, Object pk) {
        for (Table child : tables.values()) {
            for (int i = 0; i < child.getColumns().size(); i++) {
                if (parent.getName().equals(child.getColumns().get(i).refTable)) {
                    for (Object[] row : child.getRows()) {
                        if (pk.equals(row[i])) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private Table getTable(String name) {
        Table table = tables.get(name);
        if (table == null) {
            throw new DbException("Unknown table: " + name);
        }
        return table;
    }

    private void save(Table table) {
        if (autoSave) {
            repository.save(table);
        }
    }

    private List<String> tokenize(String sql) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '\'') {
                int end = sql.indexOf('\'', i + 1);
                if (end == -1) {
                    throw new DbException("Missing closing quote");
                }
                result.add(sql.substring(i, end + 1));
                i = end + 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
                int j = i;
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j))
                        || sql.charAt(j) == '_' || sql.charAt(j) == '.' || sql.charAt(j) == '-')) {
                    j++;
                }
                result.add(sql.substring(i, j));
                i = j;
            } else if (c == '<' || c == '>' || c == '!') {
                String two = sql.substring(i, Math.min(i + 2, sql.length()));
                if (two.equals("<>")) {
                    result.add("!=");
                    i += 2;
                } else if (two.equals("<=") || two.equals(">=") || two.equals("!=")) {
                    result.add(two);
                    i += 2;
                } else if (c == '!') {
                    throw new DbException("Unexpected character: !");
                } else {
                    result.add(String.valueOf(c));
                    i++;
                }
            } else if ("(),;*=".indexOf(c) != -1) {
                result.add(String.valueOf(c));
                i++;
            } else {
                throw new DbException("Unexpected character: " + c);
            }
        }
        return result;
    }

    private String next() {
        if (pos >= tokens.size()) {
            throw new DbException("Query ended unexpectedly");
        }
        pos++;
        return tokens.get(pos - 1);
    }

    private boolean accept(String word) {
        if (pos < tokens.size() && tokens.get(pos).equalsIgnoreCase(word)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!accept(word)) {
            throw new DbException("Expected " + word);
        }
    }

    private String readName() {
        String token = next();
        char first = token.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            throw new DbException("Name expected but found: " + token);
        }
        return token.toLowerCase();
    }

    private Where readWhere(Table table) {
        if (!accept("WHERE")) {
            return null;
        }
        Where where = new Where();
        while (true) {
            int p = table.columnIndex(readName());
            String op = next();
            if (!OPERATORS.contains(op)) {
                throw new DbException("Bad operator: " + op);
            }
            Object value = convert(table.getColumns().get(p).type, next());
            where.conditions.add(new Where.Condition(p, op, value));
            if (accept("AND")) {
                where.connectors.add("AND");
            } else if (accept("OR")) {
                where.connectors.add("OR");
            } else {
                break;
            }
        }
        return where;
    }

    private Object convert(String type, String token) {
        if (token.equalsIgnoreCase("NULL")) {
            return null;
        }
        try {
            if (type.equals("INT")) {
                return Integer.parseInt(token);
            } else if (type.equals("DOUBLE")) {
                return Double.parseDouble(token);
            } else if (type.equals("STRING")) {
                return removeQuotes(token);
            } else if (type.equals("DATE")) {
                return LocalDate.parse(removeQuotes(token));
            } else {
                if (token.equalsIgnoreCase("TRUE")) {
                    return true;
                } else if (token.equalsIgnoreCase("FALSE")) {
                    return false;
                }
            }
        } catch (NumberFormatException | DateTimeParseException e) {
        }
        throw new DbException("Bad value for type " + type + ": " + token);
    }

    private String removeQuotes(String token) {
        if (token.length() < 2 || !token.startsWith("'") || !token.endsWith("'")) {
            throw new DbException("Text must be in quotes: " + token);
        }
        return token.substring(1, token.length() - 1);
    }

    private String makeTable(List<String> names, List<Object[]> rows) {
        int[] width = new int[names.size()];
        for (int i = 0; i < width.length; i++) {
            width[i] = names.get(i).length();
            for (Object[] row : rows) {
                width[i] = Math.max(width[i], text(row[i]).length());
            }
        }
        StringBuilder line = new StringBuilder("+");
        for (int w : width) {
            for (int i = 0; i < w + 2; i++) {
                line.append("-");
            }
            line.append("+");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(line).append("\n");
        sb.append(makeRow(width, names.toArray())).append(line).append("\n");
        for (Object[] row : rows) {
            sb.append(makeRow(width, row));
        }
        sb.append(line).append("\n");
        return sb.toString();
    }

    private String makeRow(int[] width, Object[] cells) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < width.length; i++) {
            sb.append(String.format(" %-" + width[i] + "s |", text(cells[i])));
        }
        return sb.append("\n").toString();
    }

    private String text(Object value) {
        if (value == null) {
            return "NULL";
        }
        return value.toString();
    }
}

class Table implements Serializable {
    private final String name;
    private final List<Column> columns;
    private final int pkPos;
    private final Map<Object, Object[]> rows = new LinkedHashMap<>();
    private final List<Index> indexes = new ArrayList<>();

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = columns;
        int pk = -1;
        for (int i = 0; i < columns.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (columns.get(i).name.equals(columns.get(j).name)) {
                    throw new DbException("Duplicate column: " + columns.get(i).name);
                }
            }
            if (columns.get(i).primaryKey) {
                if (pk != -1) {
                    throw new DbException("Only one PRIMARY KEY is allowed");
                }
                pk = i;
            }
        }
        if (pk == -1) {
            throw new DbException("Table needs a PRIMARY KEY");
        }
        this.pkPos = pk;
    }

    public String getName() {
        return name;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public int getPkPos() {
        return pkPos;
    }

    public int size() {
        return rows.size();
    }

    public Collection<Object[]> getRows() {
        return rows.values();
    }

    public Object[] findByPk(Object pk) {
        return rows.get(pk);
    }

    public int columnIndex(String columnName) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equals(columnName)) {
                return i;
            }
        }
        throw new DbException("Unknown column: " + columnName);
    }

    public void insert(Object[] row) {
        Object pk = row[pkPos];
        if (pk == null) {
            throw new DbException("Primary key can't be NULL");
        }
        if (rows.containsKey(pk)) {
            throw new DbException("Duplicate primary key: " + pk);
        }
        rows.put(pk, row);
        for (Index ix : indexes) {
            ix.add(row[ix.getPos()], pk);
        }
    }

    public void delete(Object[] row) {
        Object pk = row[pkPos];
        rows.remove(pk);
        for (Index ix : indexes) {
            ix.remove(row[ix.getPos()], pk);
        }
    }

    public void update(Object[] row, int pos, Object value) {
        Object pk = row[pkPos];
        for (Index ix : indexes) {
            if (ix.getPos() == pos) {
                ix.remove(row[pos], pk);
                ix.add(value, pk);
            }
        }
        row[pos] = value;
    }

    public List<Object[]> find(Where where) {
        List<Object[]> candidates = null;
        if (where != null && !where.hasOr()) {
            for (Where.Condition c : where.conditions) {
                if (!c.op.equals("=") || c.value == null) {
                    continue;
                }
                if (c.pos == pkPos) {
                    candidates = new ArrayList<>();
                    Object[] row = rows.get(c.value);
                    if (row != null) {
                        candidates.add(row);
                    }
                    break;
                }
                Index ix = findIndex(c.pos);
                if (ix != null) {
                    candidates = new ArrayList<>();
                    for (Object pk : ix.find(c.value)) {
                        candidates.add(rows.get(pk));
                    }
                    break;
                }
            }
        }
        if (candidates == null) {
            candidates = new ArrayList<>(rows.values());
        }
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : candidates) {
            if (where == null || where.matches(row)) {
                result.add(row);
            }
        }
        return result;
    }

    private Index findIndex(int pos) {
        for (Index ix : indexes) {
            if (ix.getPos() == pos) {
                return ix;
            }
        }
        return null;
    }

    public void createIndex(String indexName, String columnName) {
        for (Index ix : indexes) {
            if (ix.getName().equals(indexName)) {
                throw new DbException("Index already exists: " + indexName);
            }
        }
        int pos = columnIndex(columnName);
        Index ix = new Index(indexName, pos);
        for (Object[] row : rows.values()) {
            ix.add(row[pos], row[pkPos]);
        }
        indexes.add(ix);
    }
}

class Column implements Serializable {
    public final String name;
    public final String type;
    public final boolean primaryKey;
    public final String refTable;

    public Column(String name, String type, boolean primaryKey, String refTable) {
        this.name = name;
        this.type = type;
        this.primaryKey = primaryKey;
        this.refTable = refTable;
    }
}

class Index implements Serializable {
    private final String name;
    private final int pos;
    private final Map<Object, List<Object>> map = new HashMap<>();

    public Index(String name, int pos) {
        this.name = name;
        this.pos = pos;
    }

    public String getName() {
        return name;
    }

    public int getPos() {
        return pos;
    }

    public void add(Object value, Object pk) {
        if (value == null) {
            return;
        }
        List<Object> list = map.get(value);
        if (list == null) {
            list = new ArrayList<>();
            map.put(value, list);
        }
        list.add(pk);
    }

    public void remove(Object value, Object pk) {
        List<Object> list = map.get(value);
        if (list != null) {
            list.remove(pk);
            if (list.isEmpty()) {
                map.remove(value);
            }
        }
    }

    public List<Object> find(Object value) {
        List<Object> list = map.get(value);
        if (list == null) {
            return new ArrayList<>();
        }
        return list;
    }
}

class Where {

    public static class Condition {
        public final int pos;
        public final String op;
        public final Object value;

        public Condition(int pos, String op, Object value) {
            this.pos = pos;
            this.op = op;
            this.value = value;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public boolean matches(Object[] row) {
            Object v = row[pos];
            if (v == null || value == null) {
                if (op.equals("=")) {
                    return v == value;
                }
                if (op.equals("!=")) {
                    return v != value;
                }
                return false;
            }
            int c = ((Comparable) v).compareTo(value);
            if (op.equals("=")) {
                return c == 0;
            } else if (op.equals("!=")) {
                return c != 0;
            } else if (op.equals("<")) {
                return c < 0;
            } else if (op.equals("<=")) {
                return c <= 0;
            } else if (op.equals(">")) {
                return c > 0;
            } else {
                return c >= 0;
            }
        }
    }

    public final List<Condition> conditions = new ArrayList<>();
    public final List<String> connectors = new ArrayList<>();

    public boolean matches(Object[] row) {
        boolean result = false;
        boolean group = true;
        for (int i = 0; i < conditions.size(); i++) {
            group = group && conditions.get(i).matches(row);
            boolean last = (i == conditions.size() - 1);
            if (last || connectors.get(i).equals("OR")) {
                result = result || group;
                group = true;
            }
        }
        return result;
    }

    public boolean hasOr() {
        return connectors.contains("OR");
    }
}

class TableRepository {
    private final File dir;

    public TableRepository(String dirName) {
        dir = new File(dirName);
        dir.mkdirs();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().endsWith(".tmp")) {
                    f.delete();
                }
            }
        }
    }

    public Map<String, Table> loadAll() {
        Map<String, Table> tables = new LinkedHashMap<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return tables;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".tbl")) {
                continue;
            }
            try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
                Table t = (Table) in.readObject();
                tables.put(t.getName(), t);
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Can't read file " + f.getName() + ", skipped");
            }
        }
        return tables;
    }

    public void save(Table table) {
        File tmp = new File(dir, table.getName() + ".tbl.tmp");
        File file = new File(dir, table.getName() + ".tbl");
        try {
            try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(tmp))) {
                out.writeObject(table);
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new DbException("Can't save table: " + e.getMessage());
        }
    }

    public void delete(String tableName) {
        new File(dir, tableName + ".tbl").delete();
    }
}

class DbException extends RuntimeException {
    public DbException(String message) {
        super(message);
    }
}

class Benchmark {

    public static void main(String[] args) throws Exception {
        int[] sizes = {10000, 50000, 100000};
        System.out.println("rows | without index, ms | with index, ms | faster");
        for (int size : sizes) {
            test(size);
        }
    }

    private static void test(int size) throws Exception {
        File dir = Files.createTempDirectory("minidb-test").toFile();
        Database db = new Database(dir.getPath());
        db.setAutoSave(false);
        db.execute("CREATE TABLE cars (id INT PRIMARY KEY, brand STRING, price DOUBLE)");

        Random random = new Random(1);
        int brands = size / 10;
        for (int i = 1; i <= size; i++) {
            db.execute("INSERT INTO cars VALUES (" + i + ", 'brand" + random.nextInt(brands) + "', 1000.5)");
        }

        String[] queries = new String[200];
        for (int i = 0; i < queries.length; i++) {
            queries[i] = "SELECT * FROM cars WHERE brand = 'brand" + random.nextInt(brands) + "'";
        }

        String[] before = new String[queries.length];
        long start = System.currentTimeMillis();
        for (int i = 0; i < queries.length; i++) {
            before[i] = db.execute(queries[i]);
        }
        long timeWithout = System.currentTimeMillis() - start;

        db.execute("CREATE INDEX idx_brand ON cars (brand)");

        start = System.currentTimeMillis();
        for (int i = 0; i < queries.length; i++) {
            String after = db.execute(queries[i]);
            if (!after.equals(before[i])) {
                throw new RuntimeException("Results are different for: " + queries[i]);
            }
        }
        long timeWith = System.currentTimeMillis() - start;

        System.out.println(size + " | " + timeWithout + " | " + timeWith + " | x"
                + (timeWithout / Math.max(timeWith, 1)));
    }
}
