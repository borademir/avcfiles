package tr.com.allianz.pmc.mnbb.workspace;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.sun.codemodel.JBlock; // NOSONAR
import com.sun.codemodel.JCodeModel; // NOSONAR
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JPackage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;
import tr.com.allianz.pmc.mnbb.config.AutoGeneratedEntityReference;
import tr.com.allianz.pmc.mnbb.mapper.BaseMapper;
import tr.com.allianz.pmc.mnbb.mapper.MapperConstant;
import tr.com.allianz.pmc.mnbb.model.BaseDto;
import tr.com.allianz.pmc.mnbb.model.BaseEntity;
import tr.com.allianz.pmc.mnbb.model.BaseOutput;

/**
 * @author Bora Demir ( @yelloware )
 * 21 Eyl 2020
 * 13:41:31
 */
@Slf4j
@UtilityClass
public class ModelGenerator {

  private static final String ANNOTATION_VALUE = "value";
  private static final String ENTITY_PACKAGE = "tr.com.allianz.pmc.mnbb.model.entity";
  private static final String DTO_PACKAGE = "tr.com.allianz.pmc.mnbb.model.dto";
  private static final String OUTPUT_PACKAGE = "tr.com.allianz.pmc.mnbb.model.output";
  private static final String MAPPER_PACKAGE = "tr.com.allianz.pmc.mnbb.mapper";

  public static void main(String[] args) throws IOException, ClassNotFoundException, SQLException {
    String ymlResourceFile = "/application-dev.yml";

    String jdbcUrl = JacksonUtils.readYmlFile(ymlResourceFile, "spring.datasource.url");
    String password = JacksonUtils.readYmlFile(ymlResourceFile, "spring.datasource.password");
    String username = JacksonUtils.readYmlFile(ymlResourceFile, "spring.datasource.username");
    String driver = JacksonUtils.readYmlFile(ymlResourceFile, "spring.datasource.driver-class-name");

    log.info("jdbc url -> {}", jdbcUrl);
    log.info("password -> {}", password);
    log.info("username -> {}", username);
    log.info("driver -> {}", driver);

    // reGenerate(jdbcUrl, password, username, driver);

    generate(jdbcUrl, password, username, driver, "CUSTOMER", "ALZ_SEQUESTERED_INSTITUTE", Collections.emptyList());
    generate(jdbcUrl, password, username, driver, "CUSTOMER", "ALZ_SEQUESTERED_INST_HIST", Collections.emptyList());

  }

  public static void reGenerate(String jdbcUrl, String password, String username, String driver) throws SQLException {
    final Reflections reflections = new Reflections("tr.com.allianz.pmc.mnbb", new SubTypesScanner(false), new TypeAnnotationsScanner());

    Set<Class<?>> entities = reflections.getTypesAnnotatedWith(Entity.class);

    for (Class<?> entity : entities) {
      log.info(entity.getCanonicalName());
      Table tableAnno = entity.getAnnotation(Table.class);
      if (Objects.nonNull(tableAnno)) {
        log.info("\t {}.{}", tableAnno.schema(), tableAnno.name());

        Field[] fields = entity.getDeclaredFields();
        List<String> pkList = new ArrayList<>();
        for (Field field : fields) {
          if (Objects.nonNull(field.getAnnotation(Id.class))) {
            Column colAnno = field.getAnnotation(Column.class);
            pkList.add(colAnno.name());
          }
        }

        generate(jdbcUrl, password, username, driver, tableAnno.schema(), tableAnno.name(), pkList);
      }
    }
  }

  private static void generate(String jdbcUrl, String password, String username, String driver, String schema, String tableName, List<String> pkList) throws SQLException {
    Connection conn = null;
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      conn = getConnection(jdbcUrl, username, password, driver);

      SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:ss");

      ps = conn.prepareStatement("select column_id,column_name,data_type,data_length,data_precision,data_scale,nullable,data_length, " +
        "(select NVL(max(1),0) from all_constraints c,all_cons_columns cc  " +
        "where  " +
        "c.owner = cc.owner and c.table_name = cc.table_name and c.constraint_name = cc.constraint_name " +
        "and c.CONSTRAINT_TYPE = 'P' and cc.column_name = cols.column_name " +
        "and c.owner = cols.owner and c.table_name = cols.table_name) as pk " +
        " from all_tab_cols cols where owner = ? and table_name = ?  " +
        "order by 9 desc,1 ");

      ps.setString(1, schema);
      ps.setString(2, tableName);

      rs = ps.executeQuery(); // NOSONAR

      JCodeModel jcodeModel = new JCodeModel();
      JPackage entityPackage = jcodeModel._package(ENTITY_PACKAGE);
      String entityClassName = toCamelCase(tableName, false) + "Entity";
      JDefinedClass entityClass = entityPackage._class(entityClassName);
      addJavaDoc(jdbcUrl, sdf, entityClass);

      JDefinedClass pkClass = entityClass._class(JMod.PUBLIC | JMod.STATIC, "PK");
      pkClass._implements(Serializable.class);
      pkClass.annotate(EqualsAndHashCode.class);
      pkClass.annotate(Setter.class);
      pkClass.annotate(Getter.class);
      String serialVersionUID = "\nprivate static final long serialVersionUID = 1L;";
      pkClass.direct(serialVersionUID);

      entityClass.annotate(Getter.class);
      entityClass.annotate(Setter.class);
      entityClass.annotate(ToString.class);
      entityClass.annotate(Entity.class);
      entityClass.annotate(IdClass.class).param(ANNOTATION_VALUE, jcodeModel.ref(pkClass.fullName()));
      entityClass.annotate(Table.class).param("name", tableName).param("schema", schema);
      entityClass._extends(BaseEntity.class);
      entityClass.direct(serialVersionUID);

      JPackage dtoPackage = jcodeModel._package(DTO_PACKAGE);
      String dtoClassName = toCamelCase(tableName, false) + "Dto";
      JDefinedClass dtoClass = dtoPackage._class(dtoClassName);
      addJavaDoc(jdbcUrl, sdf, dtoClass);
      dtoClass.annotate(Getter.class);
      dtoClass.annotate(Setter.class);
      dtoClass._extends(BaseDto.class);
      dtoClass.direct(serialVersionUID);
      dtoClass.annotate(AutoGeneratedEntityReference.class).param(ANNOTATION_VALUE, entityClass);

      JPackage outputPackage = jcodeModel._package(OUTPUT_PACKAGE);
      String ouputClassName = toCamelCase(tableName, false) + "Output";
      JDefinedClass ouputClass = outputPackage._class(ouputClassName);
      addJavaDoc(jdbcUrl, sdf, ouputClass);
      ouputClass.annotate(Getter.class);
      ouputClass.annotate(Setter.class);
      ouputClass._extends(BaseOutput.class);
      ouputClass.direct(serialVersionUID);

      JPackage mapperPackage = jcodeModel._package(MAPPER_PACKAGE);
      String mapperClassName = toCamelCase(tableName, false) + "Mapper";
      JDefinedClass mapperClass = mapperPackage._interface(mapperClassName);
      addJavaDoc(jdbcUrl, sdf, mapperClass);
      mapperClass.annotate(Mapper.class).param("componentModel", jcodeModel.ref(MapperConstant.class).staticRef("MAPPER_SPRING_BEAN"));
      mapperClass._extends(jcodeModel.ref(BaseMapper.class).narrow(entityClass, dtoClass, ouputClass));

      List<JFieldVar> pkFieldList = new ArrayList<>();

      log.info("Generating for {}.{}", schema, tableName);
      while (rs.next()) {
        String columnName = rs.getString("column_name");
        Integer dataLen = rs.getInt("data_length");
        if (Objects.isNull(dataLen)) {
          dataLen = 4000;
        }

        String dataType = rs.getString("DATA_TYPE");
        boolean boolNumber = BigDecimal.ONE.equals(rs.getBigDecimal("DATA_PRECISION"));
        boolean zeroScale = BigDecimal.ZERO.equals(rs.getBigDecimal("DATA_SCALE"));
        boolean pk = rs.getInt("PK") == 1;

        if (CollectionUtils.isNotEmpty(pkList)) {
          pk = pkList.contains(columnName);
        }

        JFieldInfo fieldInfo = createField(entityClass, columnName, dataType, boolNumber, zeroScale);

        JFieldVar entityField = fieldInfo.getJFieldVar();

        createField(dtoClass, columnName, dataType, boolNumber, zeroScale);

        JFieldInfo outputFieldInfo = createField(ouputClass, columnName, dataType, boolNumber, zeroScale);

        JFieldVar outputField = outputFieldInfo.getJFieldVar();

        if (pk) {
          entityField.annotate(Id.class);
          pkFieldList.add(entityField);

          createField(pkClass, columnName, dataType, boolNumber, zeroScale);
        }

        annotateOutputDates(outputFieldInfo, outputField, jcodeModel);

        entityField.annotate(Column.class).param("name", columnName).param("length", dataLen);

      }

      if (pkFieldList.isEmpty()) {
        throw new UnsupportedOperationException("There is no pk column for table " + tableName);
      }

      generateHashCode(jcodeModel, entityClass, pkFieldList);

      generateEquals(entityClassName, entityClass, pkFieldList);

      File projectFolder = new File(ModelGenerator.class.getResource("/").getPath()).getParentFile().getParentFile();
      File targetFolder = new File(projectFolder, "src/main/java");

      jcodeModel.build(targetFolder);

    } catch (Exception e) {
      log.error(e.getMessage(), e);
    } finally {
      if (ps != null) {
        ps.close();
      }
      if (rs != null) {
        rs.close();
      }
      if (conn != null) {
        conn.close();
      }
    }
  }

  public static void annotateEntityDates(JFieldInfo fieldInfo, JFieldVar entityField) {
    if (fieldInfo.isDateField()) {
      entityField.annotate(Temporal.class).param(ANNOTATION_VALUE, TemporalType.DATE);
    } else if (fieldInfo.isTimestampField()) {
      entityField.annotate(Temporal.class).param(ANNOTATION_VALUE, TemporalType.TIMESTAMP);
    }
  }

  private static void annotateOutputDates(JFieldInfo outputFieldInfo, JFieldVar outputField, JCodeModel jcodeModel) {
    if (outputFieldInfo.isDateField() || outputFieldInfo.isTimestampField()) {
      outputField.annotate(JsonSerialize.class).param("using", jcodeModel.ref(LocalDateTimeSerializer.class));
      outputField.annotate(JsonDeserialize.class).param("using", jcodeModel.ref(LocalDateTimeDeserializer.class));
    }
  }

  private static void addJavaDoc(String jdbcUrl, SimpleDateFormat sdf, JDefinedClass entityClass) {
    entityClass.javadoc().add("Auto generated class\n");
    entityClass.javadoc().add("@author : transformers team\n");
    entityClass.javadoc().add("@database : " + jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1) + "\n");
    entityClass.javadoc().add("@date : " + sdf.format(new Date()) + "\n");
    entityClass.javadoc().add("@see " + ModelGenerator.class);
  }

  private static void generateHashCode(JCodeModel jcodeModel, JDefinedClass jc, List<JFieldVar> pkFieldList) {
    JMethod hashCode = jc.method(JMod.PUBLIC, int.class, "hashCode");
    hashCode.annotate(Override.class);
    JBlock hashCodeBody = hashCode.body();
    JInvocation hashInvoker = jcodeModel.ref(Objects.class).staticInvoke("hash");
    pkFieldList.forEach(hashInvoker::arg);
    hashCodeBody._return(hashInvoker);
  }

  private static void generateEquals(String classname, JDefinedClass jc, List<JFieldVar> pkFieldList) {
    JMethod equals = jc.method(JMod.PUBLIC, boolean.class, "equals");
    equals.param(Object.class, "obj");
    equals.annotate(Override.class);
    JBlock equalsBody = equals.body();
    equalsBody._if(JExpr.direct("this == obj"))._then().directStatement("return true;");
    equalsBody._if(JExpr.direct("obj == null"))._then().directStatement("return false;");
    equalsBody._if(JExpr.direct("!(obj instanceof " + classname + ")"))._then().directStatement("return false;");

    equalsBody.directStatement(classname + " other = (" + classname + ") obj;");

    StringBuilder retBuilder = new StringBuilder();
    for (int i = 0; i < pkFieldList.size(); i++) {
      JFieldVar pk = pkFieldList.get(i);
      retBuilder.append("Objects.equals(" + pk.name() + ", (other." + pk.name() + "))");
      if (i != pkFieldList.size() - 1) {
        retBuilder.append(" && ");
      }
    }
    equalsBody._return(JExpr.direct(retBuilder.toString()));
  }

  private static JFieldInfo createField(JDefinedClass jc, String columnName, String dataType, boolean boolNumber, boolean zeroScale) {
    JFieldInfo info = new JFieldInfo();
    JFieldVar field = null;
    if ("VARCHAR2".equals(dataType) || "CHAR".equals(dataType)) {
      field = jc.field(JMod.PRIVATE, String.class, toCamelCase(columnName, true));
    } else if ("DATE".equals(dataType)) {
      field = jc.field(JMod.PRIVATE, LocalDateTime.class, toCamelCase(columnName, true));
      info.setDateField(true);
    } else if (dataType.startsWith("TIMESTAMP")) {
      field = jc.field(JMod.PRIVATE, LocalDateTime.class, toCamelCase(columnName, true));
      info.setTimestampField(true);
    } else if ("NUMBER".equals(dataType)) {
      field = processNumber(jc, columnName, boolNumber, zeroScale);
    } else {
      throw new UnsupportedOperationException("unknont column type : " + dataType + " for column name : " + columnName);
    }

    info.setJFieldVar(field);
    return info;
  }

  private static JFieldVar processNumber(JDefinedClass jc, String columnName, boolean boolNumber, boolean zeroScale) {
    JFieldVar field;
    if (boolNumber) {
      field = jc.field(JMod.PRIVATE, Boolean.class, toCamelCase(columnName, true));
    } else if (zeroScale) {
      field = jc.field(JMod.PRIVATE, Long.class, toCamelCase(columnName, true));
    } else {
      field = jc.field(JMod.PRIVATE, BigDecimal.class, toCamelCase(columnName, true));
    }
    return field;
  }

  private static String toCamelCase(String value, boolean startWithLowerCase) {
    String[] strings = StringUtils.split(value.toLowerCase(), "_");
    for (int i = startWithLowerCase ? 1 : 0; i < strings.length; i++) {
      strings[i] = StringUtils.capitalize(strings[i]);
    }
    return StringUtils.join(strings);
  }

  private static Connection getConnection(String pUrl, String pUser, String pPass, String driverClass) throws SQLException, ClassNotFoundException {
    Class.forName(driverClass);
    return DriverManager.getConnection(pUrl, pUser, pPass);
  }

  @Getter
  @Setter
  class JFieldInfo {
    private JFieldVar jFieldVar;
    private boolean dateField;
    private boolean timestampField;
  }

}
