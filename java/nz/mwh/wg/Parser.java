package nz.mwh.wg;
import java.util.Collections;

import nz.mwh.wg.ast.*;
import nz.mwh.wg.runtime.GraceObject;
import nz.mwh.wg.runtime.GraceString;
import nz.mwh.wg.runtime.Request;
import nz.mwh.wg.runtime.RequestPartR;

public class Parser {

    static GraceObject theParser;
    static Evaluator evaluator = new Evaluator();

    public static ASTNode parse(String input) {
        if (theParser == null) {
            evaluator.bindModule("ast", GraceASTHelps.astModule(false));
            theParser = evaluator.evaluateModule(parserAST);
        }
        Request request = new Request(new Evaluator(), Collections.singletonList(new RequestPartR("parse", Collections.singletonList(new GraceString(input)))));
        ASTNode ast = (ASTNode)theParser.request(request);
        return ast;
    }

    private static final ObjectConstructor parserAST = (ObjectConstructor) ParserData.program;
}
