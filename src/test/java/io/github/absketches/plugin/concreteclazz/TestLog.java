package io.github.absketches.plugin.concreteclazz;

import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.List;

public final class TestLog implements Log {
    final List<String> debugs = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> warns = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public boolean isDebugEnabled() {return true;}

    @Override
    public void debug(CharSequence content) {debugs.add(content.toString());}

    @Override
    public void debug(CharSequence content, Throwable error) {debugs.add(content.toString());}

    @Override
    public void debug(Throwable error) {debugs.add(error.getMessage());}

    @Override
    public boolean isInfoEnabled() {return true;}

    @Override
    public void info(CharSequence content) {infos.add(content.toString());}

    @Override
    public void info(CharSequence content, Throwable error) {infos.add(content.toString());}

    @Override
    public void info(Throwable error) {infos.add(error.getMessage());}

    @Override
    public boolean isWarnEnabled() {return true;}

    @Override
    public void warn(CharSequence content) {warns.add(content.toString());}

    @Override
    public void warn(CharSequence content, Throwable error) {warns.add(content.toString());}

    @Override
    public void warn(Throwable error) {warns.add(error.getMessage());}

    @Override
    public boolean isErrorEnabled() {return true;}

    @Override
    public void error(CharSequence content) {errors.add(content.toString());}

    @Override
    public void error(CharSequence content, Throwable error) {errors.add(content.toString());}

    @Override
    public void error(Throwable error) {errors.add(error.getMessage());}
}
