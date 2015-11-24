package org.jooby;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jruby.embed.PathType;
import org.jruby.embed.ScriptingContainer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.FileTemplateLoader;

public class JoobySiteGenerator {

  static ScriptingContainer rubyEnv = new ScriptingContainer();

  static Object script = rubyEnv.runScriptlet(PathType.CLASSPATH, "to_html.rb");

  public static void main(final String[] args) throws IOException {
    Path basedir = Paths.get("..", "jooby-project");
    Path md = process(basedir.resolve("md"));
    Path outDir = Paths.get("target", "gh-pages");
    Handlebars hbs = new Handlebars(
        new FileTemplateLoader(Paths.get("src", "main", "templates").toFile(), ".html"));
    try (Stream<Path> walk = Files.walk(md).filter(p -> {
      String name = p.getFileName().toString();
      return (name.equals("README.md") && p.getNameCount() > 1) || name.equals("index.md");
    }).sorted()) {

      Iterator<Path> it = walk.iterator();
      while (it.hasNext()) {
        Path abs = it.next();
        Path path = md.relativize(abs);
        String filename = path.toString().replace(".md", "").replace("README", "index");
        try {
          String main = Files.readAllLines(abs, StandardCharsets.UTF_8).stream()
              .collect(Collectors.joining("\n"));
          Template template = template(hbs, filename);
          Map<String, Object> data = new HashMap<>();
          String[] html = markdownToHtml(path.toString(), main);
          data.put("main", html[0]);
          data.put("toc", html[1]);
          data.put("page-header", html[2]);
          Path output = Paths.get(outDir.resolve(path).toString()
              .replace("README.md", "index.html")
              .replace("index.md", "index.html"));
          output.toFile().getParentFile().mkdirs();
          Files.write(output, Arrays.asList(template.apply(data).trim()), StandardCharsets.UTF_8);
        } catch (FileNotFoundException ex) {
          System.err.println("missing " + filename);
        }
      }
    }
    // static files
    Path staticFiles = Paths.get("src", "site-original", "resources");
    try (Stream<Path> assets = Files.walk(staticFiles)) {
      Iterator<Path> it = assets.iterator();
      while (it.hasNext()) {
        Path path = it.next();
        Path asset = outDir.resolve("resources").resolve(staticFiles.relativize(path));
        asset.toFile().getParentFile().mkdirs();
        if (!asset.toFile().exists()) {
          Files.copy(path, asset, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  private static Template template(final Handlebars hbs, final String filename) throws IOException {
    try {
      return hbs.compile(filename);
    } catch (FileNotFoundException ex) {
      if (filename.startsWith("doc/")) {
        return hbs.compile("doc/mod");
      }
      throw ex;
    }
  }

  private static String[] markdownToHtml(final String filename, final String text) {
    String input = text;
    boolean resetH1 = !filename.equals("index.md");
    switch (filename) {
      case "quickstart/index.md":
        input = input.replaceFirst("quickstart", "start");
        break;
      case "doc/index.md":
        input = input.replaceFirst("documentation\n=====", "");
        break;
    }
    Document doc = Jsoup
        .parseBodyFragment(rubyEnv.callMethod(script, "md_to_html", input).toString());

    if (resetH1) {
      Consumer<Integer> resetH = level -> {
        for (Element h : doc.select("h" + level)) {
          h.replaceWith(new Element(Tag.valueOf("h" + (level + 1)), "").text(h.text()));
        }
      };
      resetH.accept(4);
      resetH.accept(3);
      resetH.accept(2);
      resetH.accept(1);
    }

    StringBuilder toc = new StringBuilder();
    String title = null;
    toc.append("<ul>");
    String active = "active";
    for (Element h2 : doc.select("h2")) {
      StringBuilder html = new StringBuilder();
      String header = h2.text();
      if (title == null) {
        title = h2.text();
      }
      String id = id(header);
      html.append("<div class=\"datalist-title ").append(active).append("\">\n")
          .append(h2)
          .append("\n</div>\n");

      html.append("<div class=\"datalist-content\">\n");

      Element sibling = h2.nextElementSibling();
      StringBuilder subtoc = new StringBuilder();
      while (sibling != null && !sibling.tagName().equals("h2")) {
        if (sibling.tagName().equals("h3")) {
          subtoc.append("\n<li>\n<a href=\"#").append(id + "-" + id(sibling.text()))
              .append("\">").append(sibling.text())
              .append("</a></li>");
          sibling.attr("id", id + "-" + id(sibling.text()));
        }
        html.append(sibling);
        Element remove = sibling;
        sibling = sibling.nextElementSibling();
        remove.remove();
      }
      html.append("</div>\n");
      Element section = new Element(Tag.valueOf("div"), "")
          .addClass("datalist")
          .attr("id", id)
          .html(html.toString());
      h2.replaceWith(section);
      toc.append("\n<li class=\"").append(active)
          .append("\">\n<a href=\"#")
          .append(id).append("\">")
          .append(header)
          .append("</a>");
      if (subtoc.length() > 0) {
        toc.append("<ul>").append(subtoc).append("</ul>");
      }
      toc.append("</li>");
      active = "";
    }
    toc.append("\n</ul>");

    // highlight copy bar
    doc.select("div.highlighter-rouge").prepend("<div class=\"copy-bar\">\n"
        + "<span class=\"icon-clipboard-big copy-button octicon octicon-clippy\" "
        + "title=\"copy to clipboard\"></span>"
        + "</div>");
    return new String[]{doc.select("body").html(), toc.toString(), title };
  }

  private static String id(final String text) {
    return text.replaceAll("[^A-Za-z]+", "-").replaceAll("\\-+", "-");
  }

  private static Path process(final Path source) throws IOException {
    Path basedir = source.toFile().getParentFile().toPath();
    try (Stream<Path> walk = Files.walk(source)
        .filter(p -> p.getFileName().toString().endsWith(".md"))) {
      Path output = Paths.get("target", "md");
      // collect vars
      Map<String, String> links = links();
      Map<String, String> vars = new HashMap<>(links);
      vars.put("toc.md", "");
      Iterator<Path> it = walk.iterator();
      List<Path> paths = new ArrayList<>();
      while (it.hasNext()) {
        Path path = it.next();
        paths.add(path);
        // content
        String main = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
            .collect(Collectors.joining("\n"));

        String appendix = appendix(basedir, path);
        main = main.replace("{{appendix}}", appendix);
        main = main.replace("https://github.com/jooby-project/jooby/tree/master/jooby-", "/doc/");

        if (main.startsWith("---")) {
          main = main.substring(main.indexOf("---", 1) + "---".length());
        }
        for (Entry<String, String> var : links.entrySet()) {
          main = main.replace("{{" + var.getKey() + "}}", var.getValue());
        }
        vars.put(source.relativize(path).toString(), main);
      }
      // replace content
      it = paths.iterator();
      while (it.hasNext()) {
        Path path = it.next();
        String main = toString(path);
        if (main.startsWith("---")) {
          main = main.substring(main.indexOf("---", 1) + "---".length());
        }

        String appendix = appendix(basedir, path);
        main = main.replace("{{appendix}}", appendix);
        main = main.replace("https://github.com/jooby-project/jooby/tree/master/jooby-", "/doc/");

        for (Entry<String, String> var : vars.entrySet()) {
          main = main.replace("{{" + var.getKey() + "}}", var.getValue());
        }
        Path md = output.resolve(source.relativize(path));
        md.toFile().getParentFile().mkdirs();
        System.out.println("md: " + md);
        Files.write(md, Arrays.asList(main));
      }
      return output;
    }
  }

  private static String toString(final Path path) {
    return toString(path, "\n");
  }

  private static String toString(final Path path, final String nl) {
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
          .collect(Collectors.joining(nl));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String appendix(final Path basedir, final Path path) throws IOException {
    try {
      File file = path.toFile();

      // find appendix
      String name = "jooby";
      if (!file.getParentFile().getName().equals("doc")) {
        name += "-" + file.getParentFile().getName();
      }
      Path rsrc = basedir.resolve(Paths.get(name, "src", "main", "resources"));
      String level = path.toString().endsWith("/doc/index.md") ? "#" : "##";
      return Files.walk(rsrc)
          .filter(p -> p.toString().endsWith(".conf") || p.toString().endsWith(".properties"))
          .map(p -> level + " " + p.getFileName().toString() + "\n\n```properties\n"
              + toString(p, "\n\n").replaceAll("\n\n+", "\n\n")
              + "\n```\n\n")
          .collect(Collectors.joining("\n"));
    } catch (NoSuchFileException ex) {
      return "";
    }
  }

  private static Map<String, String> links() {
    Map<String, String> links = new HashMap<>();
    links.put("netty_server", "[Netty](/doc/netty)");

    links.put("undertow_server", "[Undertow](/doc/undertow)");

    links.put("site", "/");

    links.put("Jooby", "[Jooby](/)");

    links.put(
        "jetty_server",
        "[Jetty](/doc/jetty)");

    links.put(
        "rx",
        "[RxJava](https://github.com/ReactiveX/RxJava)");

    links.put(
        "ebean",
        "[Ebean ORM](http://ebean-orm.github.io)");

    links.put(
        "hazelcast",
        "[Hazelcast](http://hazelcast.org)");

    links.put(
        "less",
        "[Less](http://lesscss.org)");

    links.put(
        "less4j",
        "[Less4j](https://github.com/SomMeri/less4j)");

    links.put(
        "sass",
        "[Sass](http://sass-lang.com)");

    links.put(
        "sassjava",
        "[Vaadin Sass Compiler](https://github.com/vaadin/sass-compiler)");

    links.put(
        "flyway",
        "[Flyway](http://flywaydb.org)");

    links.put(
        "jongo",
        "[Jongo](http://jongo.org)");

    links.put(
        "commons-email",
        "[Apache Commons Email](https://commons.apache.org/proper/commons-email)");

    links.put(
        "spymemcached",
        "[SpyMemcached](https://github.com/dustin/java-memcached-client)");

    links.put(
        "memcached",
        "[Memcached](http://memcached.org)");

    links.put(
        "swagger",
        "[Swagger](http://swagger.io)");

    links.put(
        "pac4j",
        "[Pac4j](https://github.com/pac4j/pac4j)");

    links.put(
        "version",
        version());

    links.put(
        "ehcache",
        "[Ehcache](http://ehcache.org)");

    links.put(
        "site",
        "/");

    links.put(
        "apidocs",
        "/apidocs");

    links.put(
        "defdocs",
        "/apidocs/org/jooby");

    links.put(
        "maven",
        "[Maven](http://maven.apache.org/)");

    links.put(
        "guice",
        "[Guice](https://github.com/google/guice)");

    links.put(
        "jooby",
        "[Jooby](http://jooby.org)");

    links.put(
        "netty",
        "[Netty](http://netty.io)");

    links.put(
        "jetty",
        "[Jetty](http://www.eclipse.org/jetty/)");

    links.put(
        "undertow",
        "[Undertow](http://undertow.io)");

    links.put(
        "npm",
        "[npm](https://www.npmjs.com)");

    links.put(
        "grunt",
        "[npm](http://gruntjs.com)");

    links.put(
        "redis",
        "[Redis](http://redis.io)");

    links.put(
        "jedis",
        "[Jedis](https://github.com/xetorthio/jedis)");

    links.put(
        "expressjs",
        "[express.js](http://expressjs.com)");

    links.put(
        "sinatra",
        "[Sinatra](http://www.sinatrarb.com)");

    links.put(
        "spring",
        "[Spring](http://spring.io)");

    links.put(
        "jersey",
        "[Jersey](https://jersey.java.net)");

    links.put(
        "hikari",
        "[Hikari](https://github.com/brettwooldridge/HikariCP)");

    links.put(
        "mongodb",
        "[MongoDB](http://mongodb.github.io/mongo-java-driver/)");

    links.put(
        "mongodbapi",
        "http://api.mongodb.org/java/2.13/com/mongodb");

    links.put(
        "gh",
        "https://github.com/jooby-project/jooby/tree/master");

    links.put(
        "morphia",
        "https://github.com/mongodb/morphia");

    links.put(
        "morphiaapi",
        "https://rawgit.com/wiki/mongodb/morphia/javadoc/0.111/org/mongodb/morphia");

    links.put(
        "jboss-modules",
        "[JBoss Modules](https://github.com/jboss-modules/jboss-modules)");

    links.put(
        "elasticsearch",
        "[Elastic Search](https://github.com/elastic/elasticsearch)");

    return links;
  }

  private static String version() {
    return "0.11.2";
  }

}