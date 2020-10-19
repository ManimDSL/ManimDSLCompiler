parser grammar ManimParser;

options {
  tokenVocab=ManimLexer;
}

program: function* (stat SEMI)+ EOF;

function: DEF IDENT OPEN_PARENTHESIS param_list? CLOSE_PARENTHESIS COLON type? OPEN_CURLY_BRACKET (stat SEMI)+ CLOSE_CURLY_BRACKET;

param_list: param (COMMA param)*                                    #ParameterList;

param: type IDENT                                                   #Parameter;

stat: SLEEP OPEN_PARENTHESIS expr CLOSE_PARENTHESIS                 #SleepStatement
    | COMMENT OPEN_PARENTHESIS STRING CLOSE_PARENTHESIS             #CommentStatement // when string type defined we can adjust
    | LET IDENT (COLON type)? EQUAL expr                            #DeclarationStatement
    | IDENT EQUAL expr                                              #AssignmentStatement
    | method_call                                                   #MethodCallStatement
    | RETURN expr                                                   #ReturnStatement;

arg_list: expr (COMMA expr)*                                        #ArgumentList;

expr: NUMBER                                                        #NumberLiteral
    | IDENT                                                         #Identifier
    | NEW data_structure_type                                       #DataStructureContructor
    | method_call                                                   #MethodCallExpression
    | unary_operator=(ADD | MINUS) expr                             #UnaryOperator
    | expr binary_operator=(ADD | MINUS | TIMES) expr               #BinaryExpression;

method_call: IDENT DOT IDENT OPEN_PARENTHESIS arg_list? CLOSE_PARENTHESIS  #MethodCall
           | IDENT OPEN_PARENTHESIS arg_list? CLOSE_PARENTHESIS            #FunctionCall;

type: data_structure_type                                            #DataStructureType
    | primitive_type                                                 #PrimitiveType;

data_structure_type: STACK OPEN_GENERIC primitive_type CLOSE_GENERIC #StackType;
primitive_type: NUMBER_TYPE                                          #NumberType;
