package nz.mwh.wg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import nz.mwh.wg.ast.*;

import nz.mwh.wg.runtime.*;

/**
 * the Evaluator executes and manages the abstract syntax tree of grace
 * programs, a kind of runtime interpreter.
 * It handles various expression types, definitions, method declarations,
 * objects constructions, and other constructs, by visiting and processing the
 * AST nodes.
 * It facilitates the interpretation and execution of Grace programs by
 * providing a strutured way to traverse and process the AST.
 * It does this by implementing the visitor pattern for different node types in
 * the AST.
 */
public class Evaluator extends ASTConstructors implements Visitor<GraceObject> {

    private static GraceDone done = GraceDone.done;

    private Map<String, GraceObject> modules = new HashMap<>();

    // Purpose: Constructs and initializes a new object context by processing its
    // body elements, including definitions, variable declarations, imports, and
    // methods.
    // Details: Creates a BaseObject and iterates over the AST body nodes to add
    // fields and methods to the object.
    @Override
    public GraceObject visit(GraceObject context, ObjectConstructor node) {
        BaseObject object = new BaseObject(context, false, true);

        // a new BaseObject is created, initialize its reference count to 0 
        // object.incrementReferenceCount();
        // now incrementReferenceCount in GraceObject visit(GraceObject context, DefDecl node)
        System.out.println("New Object reference count is " + object.getReferenceCount());

        List<ASTNode> body = node.getBody();
        for (ASTNode part : body) {
            if (part instanceof DefDecl) {
                DefDecl def = (DefDecl) part;
                object.addField(def.getName());
            } else if (part instanceof VarDecl) {
                VarDecl var = (VarDecl) part;
                object.addField(var.getName());
                object.addFieldWriter(var.getName());
            } else if (part instanceof ImportStmt) {
                ImportStmt imp = (ImportStmt) part;
                object.addField(imp.getName());
            } else if (part instanceof MethodDecl) {
                visit(object, part);
            }
        }
        for (ASTNode part : body) {
            visit(object, part);
        }
        return object;
    }

    // Purpose: Processes a lexical request, which is a method call or message send
    // within a local context.
    // Details: Collects arguments for the request and finds the receiver object to
    // handle the request.
    @Override
    public GraceObject visit(GraceObject context, LexicalRequest node) {

        // System.out.println("GraceObject context, LexicalRequest node ");

        List<RequestPartR> parts = new ArrayList<>();
        for (Part part : node.getParts()) {
            List<GraceObject> args = part.getArgs().stream().map(x -> visit(context, x)).collect(Collectors.toList());
            parts.add(new RequestPartR(part.getName(), args));
        }
        Request request = new Request(this, parts);
        GraceObject receiver = context.findReceiver(request.getName());
        return receiver.request(request);
    }

    // Purpose: Converts a number node into a GraceNumber object.
    // Details: Simply wraps the numeric value in a GraceNumber
    @Override
    public GraceObject visit(GraceObject context, NumberNode node) {

        System.out.println("GraceObject context, NumberNode node  " + node.getValue());

        return new GraceNumber(node.getValue());
    }

    // Purpose: Converts a string node into a GraceString object.
    // Details: Simply wraps the string value in a GraceString
    @Override
    public GraceObject visit(GraceObject context, StringNode node) {

        System.out.println("GraceObject context, StringNode node  " + node.getValue());

        return new GraceString(node.getValue());
    }

    // Purpose: Handles interpolated strings, concatenating static text with
    // evaluated expressions.
    // Details: Processes each part of the interpolated string and concatenates the
    // results.
    @Override
    public GraceObject visit(GraceObject context, InterpString node) {

        System.out.println("GraceObject context, InterpString node");

        String value = node.getValue();
        ASTNode expression = node.getExpression();
        ASTNode next = node.getNext();
        StringBuilder sb = new StringBuilder();
        sb.append(value);
        sb.append(expression.accept(context, this).toString());
        while (next instanceof InterpString) {
            InterpString nextString = (InterpString) next;
            sb.append(nextString.getValue());
            expression = nextString.getExpression();
            next = nextString.getNext();
            sb.append(expression.accept(context, this).toString());
        }
        // next must now be a StringNode
        if (!(next instanceof StringNode)) {
            throw new UnsupportedOperationException("Invalid InterpString node");
        }
        StringNode sn = (StringNode) next;
        sb.append(sn.getValue());

        return new GraceString(value + expression.accept(context, this) + next.accept(context, this));
    }

    // Purpose: Defines a field in the current object context.
    // Details: Evaluates the value and assigns it to the field in the BaseObject.
    @Override
    public GraceObject visit(GraceObject context, DefDecl node) {

        System.out.println("GraceObject context, DefDecl node");

        if (context instanceof BaseObject) {
            BaseObject object = (BaseObject) context;
            GraceObject value = node.getValue().accept(context, this);
            object.setField(node.getName(), value);
            return done;
        }
        throw new UnsupportedOperationException("def can only appear inside in-code context");
    }

    // Purpose: Declares a variable and optionally initializes it.
    // Details: Creates a setter method if a value is provided.
    @Override
    public GraceObject visit(GraceObject context, VarDecl node) {

        System.out.println("GraceObject context, VarDecl node");

        if (node.getValue() != null) {
            System.out.println("GraceObject context, VarDecl node " + node.getValue());

            // Increment reference count if the value is an instance of BaseObject
            if (context instanceof BaseObject) {
                BaseObject baseValue = (BaseObject) context;
                baseValue.incrementReferenceCount();
                System.out.println("Incremented reference count for value: " + baseValue.getReferenceCount());
            }

            new LexicalRequest(new Cons<Part>(
                    new Part(node.getName() + ":=", new Cons<ASTNode>(node.getValue(), Cons.<ASTNode>nil())),
                    Cons.<Part>nil())).accept(context, this);
        }

        return done;
    }

    // Purpose: Declares a method and adds it to the current object context.
    // Details: Processes the method’s parts and body, setting up parameter handling
    // and method execution.
    @Override
    public GraceObject visit(GraceObject context, MethodDecl node) {

        System.out.println("GraceObject context, MethodDecl node");

        List<? extends Part> parts = node.getParts();
        String name = parts.stream().map(x -> x.getName() + "(" + x.getParameters().size() + ")")
                .collect(Collectors.joining(""));
        if (context instanceof BaseObject) {
            BaseObject object = (BaseObject) context;
            List<? extends ASTNode> body = node.getBody();
            object.addMethod(name, request -> {
                BaseObject methodContext = new BaseObject(context, true);
                List<RequestPartR> requestParts = request.getParts();
                for (int j = 0; j < requestParts.size(); j++) {
                    Part part = parts.get(j);
                    RequestPartR rpart = requestParts.get(j);
                    List<? extends ASTNode> parameters = part.getParameters();
                    for (int i = 0; i < parameters.size(); i++) {
                        IdentifierDeclaration parameter = (IdentifierDeclaration) parameters.get(i);
                        methodContext.addField(parameter.getName());
                        methodContext.setField(parameter.getName(), rpart.getArgs().get(i));
                    }
                }
                for (ASTNode part : body) {
                    if (part instanceof DefDecl) {
                        DefDecl def = (DefDecl) part;
                        methodContext.addField(def.getName());
                    } else if (part instanceof VarDecl) {
                        VarDecl var = (VarDecl) part;
                        methodContext.addField(var.getName());
                        methodContext.addFieldWriter(var.getName());
                    }
                }
                try {
                    GraceObject last = null;
                    for (ASTNode part : body) {
                        last = visit(methodContext, part);
                    }
                    return last;
                } catch (ReturnException re) {
                    if (re.context == methodContext) {
                        return re.getValue();
                    } else {
                        throw re;
                    }
                }
            });
            return done;
        }
        throw new UnsupportedOperationException("method can only be defined in object context");
    }

    // Purpose: Processes an explicit request (method call) with a specified
    // receiver.
    // Details: Collects arguments, builds a Request, and sends it to the receiver.
    @Override
    public GraceObject visit(GraceObject context, ExplicitRequest node) {

        // System.out.println("GraceObject context, ExplicitRequest node");

        List<RequestPartR> parts = new ArrayList<>();
        for (Part part : node.getParts()) {
            List<GraceObject> args = part.getArgs().stream().map(x -> visit(context, x)).collect(Collectors.toList());
            parts.add(new RequestPartR(part.getName(), args));
        }
        Request request = new Request(this, parts, node.location);
        GraceObject receiver = node.getReceiver().accept(context, this);
        return receiver.request(request);

    }

    // Purpose: Handles assignments to fields or variables.
    // Details: Identifies the target and updates its value through a corresponding
    // request.
    @Override
    public GraceObject visit(GraceObject context, Assign node) {

        System.out.println("GraceObject context, Assign node");

        if (node.getTarget() instanceof LexicalRequest) {
            System.out.println("LexicalRequest");
            LexicalRequest target = (LexicalRequest) node.getTarget();
            String name = target.getParts().get(0).getName();
            List<RequestPartR> parts = new ArrayList<>();
            parts.add(new RequestPartR(name + ":=", Collections.singletonList(node.getValue().accept(context, this))));
            Request request = new Request(this, parts);
            GraceObject receiver = context.findReceiver(request.getName());
            receiver.request(request);
            return done;
        } else if (node.getTarget() instanceof ExplicitRequest) {
            System.out.println("ExplicitRequest");
            ExplicitRequest target = (ExplicitRequest) node.getTarget();
            System.out.println(target.toString());
            String name = target.getParts().get(0).getName();
            System.out.println(name);
            List<RequestPartR> parts = new ArrayList<>();
            parts.add(new RequestPartR(name + ":=", Collections.singletonList(node.getValue().accept(context, this))));
            System.out.println("Collections.singletonList - the value in the node  "
                    + Collections.singletonList(node.getValue().accept(context, this)));
            System.out.println("this " + this.toString());
            Request request = new Request(this, parts);
            System.out.println("request name " + request.getName());
            GraceObject receiver = target.getReceiver().accept(context, this);
            // Print reference count if receiver is an instance of BaseObject
            if (receiver instanceof BaseObject) {
                int refCount = ((BaseObject) receiver).getReferenceCount();
                System.out.println("Receiver's reference count: " + refCount);

                // throw a fail in here if ref count > 0;

            } else {
                System.out.println("Receiver is not an instance of BaseObject");
            }

            receiver.request(request);
            return done;
        }
        throw new UnsupportedOperationException(
                "Invalid assignment to " + node.getTarget().getClass().getName() + " node");
    }

    // Purpose: Creates a block, which is a function-like construct with parameters
    // and a body.
    // Details: Wraps the block parameters and body in a GraceBlock object.
    @Override
    public GraceObject visit(GraceObject context, Block node) {

        System.out.println("GraceObject context, Block node");

        List<ASTNode> parameters = node.getParameters();
        List<ASTNode> body = node.getBody();

        return new GraceBlock(context, parameters, body);
    }

    // Purpose: Handles return statements within method bodies.
    // Details: Evaluates the return value and throws a ReturnException to exit the
    // method.
    @Override
    public GraceObject visit(GraceObject context, ReturnStmt node) {

        System.out.println("GraceObject context, ReturnStmt node");

        GraceObject value = visit(context, node.getValue());
        if (context instanceof BaseObject) {
            GraceObject returnContext = ((BaseObject) context).findReturnContext();
            throw new ReturnException(returnContext, value);
        }
        throw new UnsupportedOperationException("return can only appear inside method body");
    }

    // Purpose: Ignores comments during evaluation.
    // Details: Simply returns a GraceDone object
    @Override
    public GraceObject visit(GraceObject context, Comment node) {

        System.out.println("GraceObject context, Comment node");

        return done;
    }

    // Purpose: Imports a module and adds it to the current object context.
    // Details: Reads the module file, parses it, evaluates the AST, and binds it to
    // the current object.
    @Override
    public GraceObject visit(GraceObject context, ImportStmt node) {

        System.out.println("GraceObject context, ImportStmt node");

        if (context instanceof BaseObject) {
            BaseObject object = (BaseObject) context;

            if (modules.containsKey(node.getSource())) {
                object.setField(node.getName(), modules.get(node.getSource()));
                return done;
            }

            String filename = node.getSource() + ".grace";
            try {
                String source = Files.readString(Path.of(filename));
                ObjectConstructor ast = (ObjectConstructor) Parser.parse(source);
                GraceObject mod = this.evaluateModule(ast);
                modules.put(node.getSource(), mod);
                object.setField(node.getName(), mod);
                return done;
            } catch (IOException e) {
                throw new RuntimeException("Error reading file: " + filename);
            }
        }

        throw new UnsupportedOperationException("imports can only appear inside in-code context");
    }

    // Purpose: Creates a base object with built-in methods for the standard
    // library.
    // Details: Adds methods for printing, conditional logic, and file handling.
    static BaseObject basePrelude() {

        System.out.println("BaseObject basePrelude, the big func");

        BaseObject lexicalParent = new BaseObject(null);
        lexicalParent.addMethod("print(1)", request -> {
            System.out.println(request.getParts().get(0).getArgs().get(0).toString());
            return done;
        });
        lexicalParent.addMethod("true(0)", request -> new GraceBoolean(true));
        lexicalParent.addMethod("false(0)", request -> new GraceBoolean(false));
        lexicalParent.addMethod("if(1)then(1)else(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else {
                return request.getParts().get(2).getArgs().get(0).request(req);
            }
        });
        lexicalParent.addMethod("if(1)then(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            }
            return done;
        });
        lexicalParent.addMethod("if(1)then(1)elseif(1)then(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            List<RequestPartR> rparts = request.getParts();
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(2).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(3).getArgs().get(0).request(req);
            } else {
                return done;
            }
        });
        lexicalParent.addMethod("if(1)then(1)elseif(1)then(1)else(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            List<RequestPartR> rparts = request.getParts();
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(2).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(3).getArgs().get(0).request(req);
            } else {
                return request.getParts().get(4).getArgs().get(0).request(req);
            }
        });
        lexicalParent.addMethod("if(1)then(1)elseif(1)then(1)elseif(1)then(1)else(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            List<RequestPartR> rparts = request.getParts();
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(2).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(3).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(4).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(5).getArgs().get(0).request(req);
            } else {
                return request.getParts().get(6).getArgs().get(0).request(req);
            }
        });
        lexicalParent.addMethod("if(1)then(1)elseif(1)then(1)elseif(1)then(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            List<RequestPartR> rparts = request.getParts();
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(2).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(3).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(4).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(5).getArgs().get(0).request(req);
            } else {
                return done;
            }
        });
        lexicalParent.addMethod("if(1)then(1)elseif(1)then(1)elseif(1)then(1)elseif(1)then(1)else(1)", request -> {
            GraceBoolean condition = (GraceBoolean) request.getParts().get(0).getArgs().get(0);
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            List<RequestPartR> rparts = request.getParts();
            if (condition.getValue()) {
                return request.getParts().get(1).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(2).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(3).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(4).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(5).getArgs().get(0).request(req);
            } else if (((GraceBoolean) rparts.get(6).getArgs().get(0).request(req)).getValue()) {
                return rparts.get(7).getArgs().get(0).request(req);
            } else {
                return request.getParts().get(8).getArgs().get(0).request(req);
            }
        });
        lexicalParent.addMethod("while(1)do(1)", request -> {
            List<RequestPartR> parts = Collections.singletonList(new RequestPartR("apply", Collections.emptyList()));
            Request req = new Request(request.getVisitor(), parts);
            GraceObject condition = request.getParts().get(0).getArgs().get(0);
            GraceObject body = request.getParts().get(1).getArgs().get(0);
            while (((GraceBoolean) condition.request(req)).getValue()) {
                body.request(req);
            }
            return done;
        });
        lexicalParent.addMethod("getFileContents(1)", request -> {
            String filename = ((GraceString) request.getParts().get(0).getArgs().get(0)).getValue();
            try {
                return new GraceString(new String(Files.readAllBytes(Paths.get(filename))));
            } catch (IOException e) {
                throw new RuntimeException("Error reading file: " + filename);
            }
        });
        return lexicalParent;
    }

    // Purpose: Evaluates an AST representing a module.
    // Details: Uses basePrelude() as the context and processes the module's AST.
    public GraceObject evaluateModule(ObjectConstructor module) {

        System.out.println("ObjectConstructor module");

        return this.visit(basePrelude(), module);
    }

    // Purpose: Adds a module to the internal module map.
    // Details: Maps the module name to its GraceObject representation.
    public void bindModule(String name, GraceObject module) {

        System.out.println("String name, GraceObject module");

        modules.put(name, module);
    }

    // Purpose: Evaluates a given program AST.
    // Details: Uses basePrelude() and an evaluator instance to process the program
    // AST.

    // maybe the thing to count +++++++++++++++++++++++++++++++++++

    static GraceObject evaluateProgram(ASTNode program) {
        System.out.println("  evaluateProgram(ASTNode program   ");
        BaseObject lexicalParent = (BaseObject) basePrelude();
        return evaluateProgram(program, lexicalParent);
    }

    // Purpose: Evaluates a program AST with a specified lexical parent.
    // Details: Processes the program AST in the context of the provided BaseObject.
    static GraceObject evaluateProgram(ASTNode program, BaseObject lexicalParent) {

        System.out.println("ASTNode program, BaseObject lexicalParent");

        Evaluator evaluator = new Evaluator();
        return evaluator.visit(lexicalParent, program);
    }

}
