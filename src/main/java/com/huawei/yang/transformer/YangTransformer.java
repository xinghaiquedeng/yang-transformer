package com.huawei.yang.transformer;

import org.yangcentral.yangkit.base.*;
import org.yangcentral.yangkit.common.api.FName;
import org.yangcentral.yangkit.common.api.Namespace;
import org.yangcentral.yangkit.common.api.QName;
import org.yangcentral.yangkit.common.api.validate.ValidatorResult;
import org.yangcentral.yangkit.model.api.restriction.LeafRef;
import org.yangcentral.yangkit.model.api.restriction.Union;
import org.yangcentral.yangkit.model.api.schema.ModuleId;
import org.yangcentral.yangkit.model.api.schema.YangSchemaContext;
import org.yangcentral.yangkit.model.api.stmt.*;
import org.yangcentral.yangkit.model.api.stmt.Module;
import org.yangcentral.yangkit.model.api.stmt.type.Path;
import org.yangcentral.yangkit.model.impl.stmt.DefaultYangUnknown;
import org.yangcentral.yangkit.model.impl.stmt.ImportImpl;
import org.yangcentral.yangkit.model.impl.stmt.IncludeImpl;
import org.yangcentral.yangkit.model.impl.stmt.PrefixImpl;
import org.yangcentral.yangkit.parser.YangParserException;
import org.yangcentral.yangkit.parser.YangYinParser;
import org.yangcentral.yangkit.register.YangStatementRegister;
import org.yangcentral.yangkit.utils.file.FileUtil;
import org.yangcentral.yangkit.writter.YangFormatter;
import org.yangcentral.yangkit.writter.YangWriter;

import org.dom4j.DocumentException;
import org.yangcentral.yangkit.xpath.impl.YangXPathPrefixVisitor;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * 功能描述
 *
 * @author f00360218
 * @since 2022-05-13
 */
public class YangTransformer {


    public static boolean hasInActiveDescendant(SchemaNodeContainer schemaNodeContainer){
        for(SchemaNode child:schemaNodeContainer.getSchemaNodeChildren()){
            if(!child.isActive()){
                return true;
            }
            if(child instanceof SchemaNodeContainer){
                if(hasInActiveDescendant((SchemaNodeContainer) child)){
                    return true;
                }
            }
        }
        return false;
    }

    private static Import getImport(Module curModule, String importModule){
        List<Import> imports =  curModule.getImports();
        for(Import im:imports){
            if(im.getArgStr().equals(importModule)){
                return im;
            }
        }
        return null;
    }

    private static Import getDeepImport(Module curModule, String importModule) {
        List<Import> imports = new ArrayList<>();
        getImList(curModule, imports);
        for (Import im : imports) {
            if (im.getArgStr().equals(importModule)) {
                return im;
            }
        }
        return null;
    }

    private static void getImList(Module curModule, List<Import> importList) {
        YangSchemaContext yangSchemaContext = curModule.getContext().getSchemaContext();
        List<Import> imports = curModule.getImports();
        for (Import im : imports) {
            if (!containImStr(importList, im.getArgStr())) {
                importList.add(im);
                List<Module> modules = yangSchemaContext.getModule(im.getArgStr());
                if (modules.isEmpty()) {
                    continue;
                }
                Module nextModule = modules.get(0);
                getImList(nextModule, importList);
            }
        }
    }

    private static boolean containImStr(List<Import> imports, String moduleStr) {
        for (Import im : imports) {
            if (im.getArgStr().equals(moduleStr)) {
                return true;
            }
        }
        return false;
    }

    private static Include getInclude(Module curModule, String includeModule){
        List<Include> includes =  curModule.getIncludes();
        for(Include include:includes){
            if(include.getArgStr().equals(includeModule)){
                return include;
            }
        }
        return null;
    }

    private static LinkageInfo genLinkageInfoForIdentifierRef(Module curModule,IdentifierRef statement){
        YangStatement referStmt = statement.getReferenceStatement();
        if(referStmt == null){
            return null;
        }
        Module originalModule = referStmt.getContext().getCurModule();
        if(originalModule == curModule){
            //the same module or submodule,no linkage should be added
            return null;
        }
        if(originalModule.getMainModule() == curModule.getMainModule()){
            //belongs to the same module but in several submodules, a new include should be added.
            Include include = getInclude(curModule,originalModule.getArgStr());
            if(null == include){
                LinkageInfo linkageInfo = new LinkageInfo(LinkageType.INCLUDE,originalModule.getModuleId());
                return linkageInfo;
            }
            return null;
        }
        else {
            //belongs to other modules, a new import should be added
            Import im = getImport(curModule,originalModule.getMainModule().getArgStr());
            LinkageInfo linkageInfo = null;
            if( im == null){
                linkageInfo = new LinkageInfo(LinkageType.IMPORT,originalModule.getMainModule().getModuleId());
                linkageInfo.setPrefix(originalModule.getMainModule().getSelfPrefix());
            }
            //process argument
            YangStatement yangStatement = (YangStatement) statement;
            String localArg = new FName(yangStatement.getArgStr()).getLocalName();
            yangStatement.setArgStr(
                    ((im != null) ? im.getPrefix().getArgStr() : originalModule.getMainModule().getSelfPrefix()) + ":" + localArg);

            return linkageInfo;
        }
    }
    private static List<TransformerResult> transformXPathSupport(Module curModule,XPathSupport xPathSupport){
        List<TransformerResult> transformerResults = new ArrayList<>();
        YangStatement statement = (YangStatement) xPathSupport;
        YangXPathTransformerVisitor visitor = new YangXPathTransformerVisitor(xPathSupport,curModule);
        transformerResults.addAll(visitor.visit(((XPathSupport) statement).getXPathExpression().getRootExpr(), (XPathSupport) statement));
        for(TransformerResult transformerResult:transformerResults){
            if(transformerResult.getType() != TransformerType.ADD){
                continue;
            }
            if(transformerResult.getTarget() == curModule){
                List<TransformerStatement> transformerStatements = transformerResult.getStatements();
                List<Integer> deletedSeqs = new ArrayList<>();
                for(int i = 0;i <transformerStatements.size();i++){
                    TransformerStatement transformerStatement = transformerStatements.get(i);
                    if(transformerStatement.getStatement() instanceof Import){
                        Import im = (Import) transformerStatement.getStatement();
                        Import orig = (Import) curModule.getSubStatement(YangBuiltinKeyword.IMPORT.getQName(),im.getArgStr());
                        if(orig != null ){
                            if(!orig.getSubStatement(YangBuiltinKeyword.PREFIX.getQName()).get(0).getArgStr().equals(
                                    im.getSubStatement(YangBuiltinKeyword.PREFIX.getQName()).get(0).getArgStr())){
                                String newAgr = statement.getArgStr().replaceAll(im.getSubStatement(YangBuiltinKeyword.PREFIX.getQName()).get(0).getArgStr()+":",
                                        orig.getSubStatement(YangBuiltinKeyword.PREFIX.getQName()).get(0).getArgStr()+":");
                                statement.setArgStr(newAgr);
                            }
                            deletedSeqs.add(i);
                        }
                    }
                }
                for(int seq:deletedSeqs){
                    transformerStatements.remove(seq);
                }
            }
        }
        return transformerResults;
    }

    private static YangStatement getParentNoUses(Uses target){
        if(target.getParentSchemaNode() instanceof Uses){
            return getParentNoUses((Uses) target.getParentSchemaNode());
        }
        return (YangStatement) target.getParentSchemaNode();
    }
    private static void transformRefine(Refine refine){
        SchemaNode target = refine.getTarget();
        if(refine.getDefaults().size() > 0){
            List<YangStatement> defaults = target.getSubStatement(YangBuiltinKeyword.DEFAULT.getQName());
            if(defaults.size() > 0){
                for(YangStatement dflt:defaults){
                    target.removeChild(dflt);
                }
            }
            for(Default dflt: refine.getDefaults()){
                target.addChild(dflt);
            }
        }
        if(refine.getDescription() != null){
            List<YangStatement> descriptions = target.getSubStatement(YangBuiltinKeyword.DESCRIPTION.getQName());
            if(descriptions.size() > 0){
                target.removeChild(descriptions.get(0));
            }
            target.addChild(refine.getDescription());
        }
        if(refine.getReference() != null){
            List<YangStatement> references = target.getSubStatement(YangBuiltinKeyword.REFERENCE.getQName());
            if(references.size() > 0){
                target.removeChild(references.get(0));
            }
            target.addChild(refine.getReference());
        }
        if(refine.getConfig() != null){
            List<YangStatement> configs = target.getSubStatement(YangBuiltinKeyword.CONFIG.getQName());
            if(configs.size() > 0){
                target.removeChild(configs.get(0));
            }
            target.addChild(refine.getConfig());
        }
        if(refine.getMandatory() != null){
            List<YangStatement> mandatorys = target.getSubStatement(YangBuiltinKeyword.MANDATORY.getQName());
            if(mandatorys.size() > 0){
                target.removeChild(mandatorys.get(0));
            }
            target.addChild(refine.getMandatory());
        }
        if(refine.getPresence() != null){
            List<YangStatement> presences = target.getSubStatement(YangBuiltinKeyword.PRESENCE.getQName());
            if(presences.size() > 0){
                target.removeChild(presences.get(0));
            }
            target.addChild(refine.getPresence());
        }
        if(refine.getMusts().size() > 0){
            for(Must must: refine.getMusts()){
                target.addChild(must);
            }
        }
        if(refine.getMinElements() != null){
            List<YangStatement> minElementses = target.getSubStatement(YangBuiltinKeyword.MINELEMENTS.getQName());
            if(minElementses.size() > 0){
                target.removeChild(minElementses.get(0));
            }
            target.addChild(refine.getMinElements());
        }
        if(refine.getMaxElements() != null){
            List<YangStatement> maxElementses = target.getSubStatement(YangBuiltinKeyword.MAXELEMENTS.getQName());
            if(maxElementses.size() > 0){
                target.removeChild(maxElementses.get(0));
            }
            target.addChild(refine.getMaxElements());
        }
        if(refine.getIfFeatures().size() > 0){
            for(IfFeature ifFeature: refine.getIfFeatures()){
                target.addChild(ifFeature);
            }
        }
        if(refine.getUnknowns().size() > 0){
            YangSpecification yangSpecification = target.getContext().getYangSpecification();
            YangStatementDef yangStatementDef = yangSpecification.getStatementDef(target.getYangKeyword());
            for(YangUnknown unknown:refine.getUnknowns()) {
                boolean isMultiInstance = true;
                Cardinality cardinality = yangStatementDef.getSubStatementCardinality(unknown.getYangKeyword());
                if (cardinality != null) {
                    if (!cardinality.isUnbounded() && cardinality.getMaxElements() <= 1) {
                        isMultiInstance = false;
                    }
                }
                if (isMultiInstance) {
                    //                    YangUnknown orig = (YangUnknown) target.getSubStatement(unknown.getYangKeyword(), unknown.getArgStr());
                    //                    if (orig != null) {
                    //                        target.removeChild(orig);
                    //
                    //                    }
                } else {
                    List<YangStatement> origs = target.getSubStatement(unknown.getYangKeyword());
                    if (!origs.isEmpty()) {
                        target.removeChild(origs.get(0));
                    }
                }
                target.addChild(unknown);
            }
        }
    }
    private static List<TransformerResult> transformExpand(SchemaNode schemaNode){
        List<TransformerResult> transformerResults = new ArrayList<>();
        if(schemaNode instanceof Uses){
            Uses uses = (Uses) schemaNode;
            YangStatement target = (YangStatement) schemaNode.getParentSchemaNode();
            if(target== null || !(target instanceof SchemaNode)){
                target = schemaNode.getParentStatement();
            } else {
                if (target instanceof Uses) {
                    target = getParentNoUses((Uses) target);
                }
                else if(target instanceof Case){
                    Case c = (Case) target;
                    if(c.isShortCase()){
                        target = c.getParent();
                    }
                }
            }
            if(uses.getRefines().size() > 0){
                for(Refine refine:uses.getRefines()){
                    transformRefine(refine);
                }
            }
            if(uses.getAugments().size() > 0){
                for(Augment augment:uses.getAugments()){
                    SchemaNode augmentTarget = augment.getTarget();
                    List<SchemaNode> schemaNodes = augment.getSchemaNodeChildren();
                    for(SchemaNode son:schemaNodes){
                        augmentTarget.addChild(son);
                    }
                }
            }
            //if (hasInActiveDescendant(uses)) {
            TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE, target, new ArrayList<>());
            transformerResult.addStatement(null, schemaNode);
            transformerResults.add(transformerResult);
            uses.getRefGrouping().delReference(uses);
            List<SchemaNode> schemaNodes = uses.getSchemaNodeChildren();
            TransformerResult sonTransformerResult = new TransformerResult(TransformerType.ADD, target, new ArrayList<>());
            YangStatement pre = uses;
            for (SchemaNode sonSchemaNode : schemaNodes) {
                sonTransformerResult.addStatement(pre, sonSchemaNode);
                pre = sonSchemaNode;
            }
            transformerResults.add(sonTransformerResult);
            //return transformerResults;
            //}
        }
        if(schemaNode instanceof SchemaNodeContainer){
            transformerResults.addAll(procSchemaNodeChildren((SchemaNodeContainer) schemaNode));
        }
        return transformerResults;
    }

    private static boolean isUnUsed(YangStatement statement){
        YangStatement ancestor = getAncestorStatement(statement);
        if( !(ancestor instanceof Module)){
            //it means this statement has been deleted from module
            return true;
        }
        YangContext context = statement.getContext();
        if(null == context){
            return true;
        }
        Grouping curGrouping = context.getCurGrouping();
        if(curGrouping != null){
            //if this statement in grouping, if the grouping is unused, so does this statement
            if(isUnUsed(curGrouping)){
                return true;
            }
        }
        if(statement instanceof Referencable){
            //if statement is referencable, need judge all refs
            Referencable referencable = (Referencable) statement;
            List<YangStatement> refs = referencable.getReferencedBy();
            for(YangStatement ref:refs){
                if(!isUnUsed(ref)){
                    return false;
                }
            }
            return true;
        } else if (statement instanceof SchemaNode){
            SchemaNode schemaNode = (SchemaNode) statement;
            //            if(!schemaNode.isActive()){
            //                return true;
            //            }
        } else if(statement instanceof Type){
            Type type = (Type) statement;
            YangStatement parent = type.getParentStatement();
            if(isUnUsed(parent)){
                return true;
            }
        }
        return false;
    }
    private static TypedefContainer getClosestTypedefContainer(YangStatement statement){
        if(statement instanceof TypedefContainer){
            return (TypedefContainer) statement;
        }
        YangStatement parent = statement.getParentStatement();
        if(parent != null){
            return getClosestTypedefContainer(parent);
        }
        return null;
    }
    private static boolean canMigrate(Type type,SchemaNode targetNode){
        if(type.getRestriction() instanceof Union){
            Union union = (Union) type.getRestriction();
            for(Type subType:union.getTypes()){
                if(!canMigrate(subType,targetNode)){
                    return false;
                }
            }
            return true;
        }
        if(type.getRestriction() instanceof LeafRef){
            LeafRef leafRef = (LeafRef) type.getRestriction();
            Path refPath = leafRef.getEffectivePath();
            return canMigrate(refPath,targetNode,refPath.getContext().getCurModule());
        }
        return true;
    }
    private static boolean canMigrate(XPathSupport xPathSupport,SchemaNode targetNode,Module curModule){
        YangXPathPrefixVisitor prefixVisitor = new YangXPathPrefixVisitor(xPathSupport,curModule);
        List<String> result = prefixVisitor.visit(xPathSupport.getXPathExpression().getRootExpr(),xPathSupport);
        //remove duplicate prefixes
        List<String> prefixes = new ArrayList<>();
        for(String prefix:result){
            if(prefixes.contains(prefix)){
                continue;
            }
            prefixes.add(prefix);
        }
        return canMigrate(prefixes,targetNode,curModule);
    }

    private static boolean canMigrate(List<String> prefixes,SchemaNode targetNode,Module curModule){

        if(targetNode == null){
            return false;
        }
        boolean canMigrate = true;
        for(String prefix:prefixes){
            Optional<ModuleId> moduleOp = curModule.findModuleByPrefix(prefix);
            if(moduleOp.isPresent()){
                Namespace targetNs = targetNode.getContext().getNamespace();
                List<Module> modules = targetNode.getContext().getSchemaContext()
                        .getModule(targetNs.getUri());
                if(modules.isEmpty()){
                    canMigrate = false;
                    break;
                }
                Module targetModule = modules.get(0);
                Optional<Module> importModuleOp = curModule.getContext().getSchemaContext()
                        .getModule(moduleOp.get());
                if(!importModuleOp.isPresent()){
                    canMigrate = false;
                    break;
                }
                if(getDeepImport(importModuleOp.get(),targetModule.getArgStr()) != null){
                    //circle dependency
                    canMigrate = false;
                    break;
                }
            }
        }
        return canMigrate;
    }
    private static boolean transformDeviateAdd(List<TransformerResult> transformerResults,
                                               Deviate deviate,Module curModule){
        boolean deviateMigrate = true;
        SchemaNode targetNode = deviate.getTarget();
        TransformerResult delResult = new TransformerResult(TransformerType.DELETE,
                deviate,new ArrayList<>());
        transformerResults.add(delResult);
        TransformerResult transformerResult = new TransformerResult(TransformerType.ADD,targetNode,new ArrayList<>());
        transformerResults.add(transformerResult);
        //must
        List<Must> musts = deviate.getMusts();
        if(musts.size() > 0){
            for(Must must:musts){
                boolean canMigrate = canMigrate(must,targetNode,curModule);;
                if(canMigrate){
                    transformerResult.addStatement(null,must);
                    delResult.addStatement(null,must);
                } else {
                    deviateMigrate = false;
                }

            }
        }
        //default
        List<Default> defaults = deviate.getDefaults();
        if(!defaults.isEmpty()) {
            for(Default dflt:defaults){
                transformerResult.addStatement(null,dflt);
                delResult.addStatement(null,dflt);
            }
        }
        //mandatory
        Mandatory mandatory = deviate.getMandatory();
        if(mandatory != null){
            transformerResult.addStatement(null,mandatory);
            delResult.addStatement(null,mandatory);
        }
        //max-elements
        MaxElements maxElements = deviate.getMaxElements();
        if(maxElements != null){
            transformerResult.addStatement(null,maxElements);
            delResult.addStatement(null,maxElements);
        }
        //min-elements
        MinElements minElements = deviate.getMinElements();
        if(minElements != null){
            transformerResult.addStatement(null,minElements);
            delResult.addStatement(null,minElements);
        }
        //unique
        List<Unique> uniques = deviate.getUniques();
        if(!uniques.isEmpty()){
            for(Unique unique:uniques){
                transformerResult.addStatement(null,unique);
                delResult.addStatement(null,unique);
            }
        }
        //units
        Units units = deviate.getUnits();
        if(units != null){
            transformerResult.addStatement(null,units);
            delResult.addStatement(null,units);
        }
        //unknowns
        List<YangUnknown> unknowns = deviate.getUnknowns();
        if(!unknowns.isEmpty()){
            for(YangUnknown unknown:unknowns){
                transformerResult.addStatement(null,unknown);
                delResult.addStatement(null,unknown);
            }

        }
        return deviateMigrate;
    }
    private static boolean transformDeviateDelete(List<TransformerResult> transformerResults,
                                                  Deviate deviate,Module curModule){
        SchemaNode targetNode = deviate.getTarget();
        TransformerResult delResult = new TransformerResult(TransformerType.DELETE,
                deviate,new ArrayList<>());
        transformerResults.add(delResult);
        TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE,targetNode,new ArrayList<>());
        transformerResults.add(transformerResult);
        //must
        List<Must> musts = deviate.getMusts();
        if(musts.size() > 0){
            for(Must must:musts){
                YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.MUST.getQName(),must.getArgStr());
                if(targetStmt == null){
                    continue;
                }
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,must);
            }
        }
        //default
        List<Default> defaults = deviate.getDefaults();
        if(!defaults.isEmpty()){
            for(Default dflt: defaults){
                YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.DEFAULT.getQName(),
                        dflt.getArgStr());
                if(targetStmt == null){
                    continue;
                }
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,dflt);
            }
        }
        //mandatory
        Mandatory mandatory = deviate.getMandatory();
        if(mandatory != null){
            YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.MANDATORY.getQName(),
                    mandatory.getArgStr());
            if(targetStmt != null){
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,mandatory);
            }
        }
        //max-elements
        MaxElements maxElements = deviate.getMaxElements();
        if(maxElements != null){
            YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.MAXELEMENTS.getQName(),
                    maxElements.getArgStr());
            if(targetStmt != null){
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,maxElements);
            }
        }
        //min-elements
        MinElements minElements = deviate.getMinElements();
        if(minElements != null){
            YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.MINELEMENTS.getQName(),
                    minElements.getArgStr());
            if(targetStmt != null){
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,minElements);
            }
        }
        //unique
        List<Unique> uniques = deviate.getUniques();
        if(!uniques.isEmpty()){
            for(Unique unique:uniques){
                YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.UNIQUE.getQName(),
                        unique.getArgStr());
                if(targetStmt == null){
                    continue;
                }
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,unique);
            }
        }
        //units
        Units units = deviate.getUnits();
        if(units != null){
            YangStatement targetStmt = targetNode.getSubStatement(YangBuiltinKeyword.UNITS.getQName(),
                    units.getArgStr());
            if(targetStmt != null){
                transformerResult.addStatement(null,targetStmt);
                delResult.addStatement(null,units);
            }
        }
        //unknowns
        List<YangUnknown> unknowns = deviate.getUnknowns();
        for(YangUnknown unknown:unknowns){
            YangStatement targetStmt = targetNode.getSubStatement(unknown.getYangKeyword(), unknown.getArgStr());
            if(targetStmt == null){
                continue;
            }
            transformerResult.addStatement(null,targetStmt);
            delResult.addStatement(null,unknown);
        }
        return true;
    }
    private static boolean transformDeviateReplace(List<TransformerResult> transformerResults,Deviate deviate,
                                                   Module curModule){
        SchemaNode targetNode = deviate.getTarget();
        boolean deviateMigrate = true;
        TransformerResult delResult = new TransformerResult(TransformerType.DELETE,
                deviate,new ArrayList<>());
        transformerResults.add(delResult);
        //type
        Type type = deviate.getType();
        if(type != null){
            if(!canMigrate(type,targetNode)){
                deviateMigrate = false;
            } else {
                List<YangStatement> targets = targetNode.getSubStatement(type.getYangKeyword());
                if(!targets.isEmpty()){
                    YangStatement targetType = targets.get(0);
                    int pos = targetNode.getChildIndex(targetType);
                    targetNode.updateChild(pos,type);
                    if(type.isDerivedType() ||(type.getRestriction() instanceof Union)){
                        String arg = type.getBuiltinType().getArgStr();
                        Type newType = (Type) YangStatementRegister.getInstance().getYangStatementInstance(YangBuiltinKeyword.TYPE.getQName(), arg);
                        List<YangStatement> subStatements = type.getEffectiveSubStatements();
                        for(YangStatement subStatement:subStatements){
                            newType.addChild(subStatement);
                        }
                        newType.setContext(new YangContext(targetNode.getContext()));
                        //newType.init();
                        //newType.build();
                        targetNode.updateChild(pos,newType);
                        if(type.isDerivedType()){
                            TypedDataNode target = (TypedDataNode) targetNode;
                            Typedef typedef = type.getDerived();
                            //default
                            boolean hasDefault =false;
                            if(target instanceof Leaf){
                                if(((Leaf) target).getDefault()!= null){
                                    hasDefault = true;
                                }
                            } else {
                                LeafList leafList = (LeafList) target;
                                if(!leafList.getDefaults().isEmpty()){
                                    hasDefault = true;
                                }
                            }
                            if(!hasDefault){
                                if(typedef.getDefault() != null){
                                    TransformerResult addTransformerResult = new TransformerResult(TransformerType.ADD, targetNode,new ArrayList<>());
                                    addTransformerResult.addStatement(null,typedef.getDefault());
                                    transformerResults.add(addTransformerResult);
                                }
                            }
                            //units
                            if((target.getUnits() == null) && (typedef.getUnits() != null)){
                                TransformerResult addTransformerResult = new TransformerResult(TransformerType.ADD, targetNode,new ArrayList<>());
                                addTransformerResult.addStatement(null,typedef.getUnits());
                                transformerResults.add(addTransformerResult);
                            }


                        }
                    }
                    delResult.addStatement(null,type);
                }
            }


        }
        //config
        Config config = deviate.getConfig();
        if (config != null){
            List<YangStatement> targetStmts = targetNode.getSubStatement(config.getYangKeyword());
            if(targetStmts.isEmpty()){
                TransformerResult addTransformerResult = new TransformerResult(TransformerType.ADD, targetNode,new ArrayList<>());
                addTransformerResult.addStatement(null,config);
                transformerResults.add(addTransformerResult);
            }
            else {
                YangStatement targetStmt = targetStmts.get(0);
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,config);
            }
            delResult.addStatement(null,config);
        }
        //default
        List<Default> defaults = deviate.getDefaults();
        if(!defaults.isEmpty()){
            if(targetNode instanceof LeafList){
                for(Default dflt:defaults){
                    YangStatement targetStmt = targetNode.getSubStatement(dflt.getYangKeyword(), dflt.getArgStr());
                    if(targetStmt == null){
                        continue;
                    }
                    int pos = targetNode.getChildIndex(targetStmt);
                    targetNode.updateChild(pos,dflt);
                    delResult.addStatement(null,dflt);
                }
            } else {
                Default dflt = defaults.get(0);
                List<YangStatement> targets = targetNode.getSubStatement(dflt.getYangKeyword());
                if(!targets.isEmpty()){
                    YangStatement targetStmt = targets.get(0);
                    int pos = targetNode.getChildIndex(targetStmt);
                    targetNode.updateChild(pos,dflt);
                    delResult.addStatement(null,dflt);
                }

            }
        }
        //mandatory
        Mandatory mandatory = deviate.getMandatory();
        if(mandatory != null){
            List<YangStatement> targets = targetNode.getSubStatement(mandatory.getYangKeyword());
            if(!targets.isEmpty()){
                YangStatement targetStmt = targets.get(0);
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,mandatory);
                delResult.addStatement(null,mandatory);
            }
        }
        //max-elements
        MaxElements maxElements = deviate.getMaxElements();
        if(maxElements != null){
            List<YangStatement> targets = targetNode.getSubStatement(maxElements.getYangKeyword());
            if(!targets.isEmpty()){
                YangStatement targetStmt = targets.get(0);
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,maxElements);
                delResult.addStatement(null,maxElements);
            }

        }
        //min-elements
        MinElements minElements = deviate.getMinElements();
        if(minElements != null){
            List<YangStatement> targets = targetNode.getSubStatement(minElements.getYangKeyword());
            if(!targets.isEmpty()){
                YangStatement targetStmt = targets.get(0);
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,minElements);
                delResult.addStatement(null,minElements);
            }

        }
        //units
        Units units = deviate.getUnits();
        if(units != null){
            List<YangStatement> targets = targetNode.getSubStatement(units.getYangKeyword());
            if(!targets.isEmpty()){
                YangStatement targetStmt = targets.get(0);
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,units);
                delResult.addStatement(null,units);
            }

        }
        //must
        List<Must> musts = deviate.getMusts();
        if(!musts.isEmpty()){
            for(Must must:musts){
                boolean canMigrate = canMigrate(must,targetNode,curModule);
                if(canMigrate){
                    YangStatement targetStmt = targetNode.getSubStatement(must.getYangKeyword(),must.getArgStr());
                    if(targetStmt == null){
                        continue;
                    }
                    int pos = targetNode.getChildIndex(targetStmt);
                    targetNode.updateChild(pos,must);
                    delResult.addStatement(null,must);
                } else {
                    deviateMigrate = false;
                }

            }
        }
        //unique
        List<Unique> uniques = deviate.getUniques();
        if(!uniques.isEmpty()){
            for(Unique unique:uniques){
                YangStatement targetStmt = targetNode.getSubStatement(unique.getYangKeyword(),unique.getArgStr());
                int pos = targetNode.getChildIndex(targetStmt);
                targetNode.updateChild(pos,unique);
                delResult.addStatement(null,unique);
            }
        }
        //unknowns
        List<YangUnknown> unknowns = deviate.getUnknowns();
        if(!unknowns.isEmpty()){
            YangSpecification yangSpecification = targetNode.getContext().getYangSpecification();
            YangStatementDef statementDef = yangSpecification.getStatementDef(targetNode.getYangKeyword());

            for(YangUnknown unknown:unknowns){
                boolean multiInstance =true;
                if(statementDef != null){
                    Cardinality cardinality = statementDef.getSubStatementCardinality(unknown.getYangKeyword());
                    if(cardinality != null && !cardinality.isUnbounded() && cardinality.getMaxElements() <=1){
                        multiInstance = false;
                    }
                }
                if(multiInstance){
                    YangStatement targetStmt = targetNode.getSubStatement(unknown.getYangKeyword(),
                            unknown.getArgStr());
                    if(targetStmt == null){
                        continue;
                    }
                    int pos = targetNode.getChildIndex(targetStmt);
                    targetNode.updateChild(pos,unknown);
                } else {
                    List<YangStatement> targets = targetNode.getSubStatement(unknown.getYangKeyword());
                    if(!targets.isEmpty()){
                        YangStatement targetStmt = targets.get(0);
                        int pos = targetNode.getChildIndex(targetStmt);
                        targetNode.updateChild(pos,unknown);
                    }
                }
                delResult.addStatement(null,unknown);
            }
        }
        return deviateMigrate;
    }
    private static boolean transformDeviate(List<TransformerResult> transformerResults,
                                            Deviate deviate,Module curModule){

        SchemaNode targetNode = deviate.getTarget();
        boolean deviateMigrate = true;
        TransformerResult delResult = new TransformerResult(TransformerType.DELETE,
                deviate,new ArrayList<>());
        transformerResults.add(delResult);
        switch (deviate.getDeviateType()){
            case ADD:{
                deviateMigrate = transformDeviateAdd(transformerResults,deviate,curModule);
                break;
            }
            case DELETE:{
                deviateMigrate = transformDeviateDelete(transformerResults,deviate,curModule);
                break;
            }
            case REPLACE:{
                deviateMigrate = transformDeviateReplace(transformerResults,deviate,curModule);
                break;
            }
        }
        if(deviateMigrate){
            TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE,
                    deviate.getParentStatement(),new ArrayList<>());
            transformerResult.addStatement(null,deviate);
            transformerResults.add(transformerResult);
        }
        return deviateMigrate;
    }

    private static void transformDeviation(List<TransformerResult> transformerResults,Deviation deviation,Module curModule){
        SchemaNode targetNode = deviation.getTarget();
        if(targetNode == null){
            return;
        }
        boolean deviationMigrate = true;
        if (deviation.getDeviates().size() == 1) {
            Deviate deviate = deviation.getDeviates().get(0);
            if (deviate.getDeviateType() == DeviateType.NOT_SUPPORTED) {
                TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE, deviation.getParentStatement(),
                        new ArrayList<>());
                transformerResult.addStatement(null, deviation);
                transformerResults.add(transformerResult);
                return;
            }
        }
        for(Deviate deviate: deviation.getDeviates()){
            boolean deviateMigrate = transformDeviate(transformerResults,deviate,curModule);
            if(!deviateMigrate){
                deviationMigrate = false;
            }
        }
        if(deviationMigrate){
            TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE,deviation.getParentStatement(),new ArrayList<>());
            transformerResult.addStatement(null,deviation);
            transformerResults.add(transformerResult);
        }
    }

    /**
     * delete deviation(not-supported),inactive node, delete uses who has inactive descendent and add active node.
     * @param curModule
     * @param statement
     * @return
     */
    private static List<TransformerResult> transformStmt(Module curModule,YangStatement statement) {
        List<TransformerResult> transformerResults = new ArrayList<>();
        if (statement instanceof Deviation) {
            Deviation deviation = (Deviation) statement;
            transformDeviation(transformerResults,deviation,curModule);

        } else if (statement instanceof SchemaNode) {
            SchemaNode schemaNode = (SchemaNode) statement;
            if(!schemaNode.isActive()){
                YangStatement target = schemaNode.getParentStatement();
                if(target instanceof Case){
                    Case c = (Case) target;
                    if(c.isShortCase()){
                        target = c.getParent();
                    }
                }
                TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE, target, new ArrayList<>());
                transformerResult.addStatement(null, statement);
                transformerResults.add(transformerResult);
                return transformerResults;
            }

        }

        transformerResults.addAll(procSubElement(curModule,statement,TransformerPhase.PHASE_STATEMENT));


        return transformerResults;
    }

    public static TransformerResult getTransformerResultFromLinkage(LinkageInfo linkageInfo,Module curModule){
        if(linkageInfo == null){
            return null;
        }
        YangStatement candidate = null;
        if(linkageInfo.getType() == LinkageType.IMPORT){
            YangContext newImportContext = new YangContext(curModule.getContext());
            Import newImport = new ImportImpl(linkageInfo.getModuleId().getModuleName());
            newImport.setContext(newImportContext);
            Prefix prefixStmt = new PrefixImpl(linkageInfo.getPrefix());
            YangContext prefixContext = new YangContext(newImportContext);
            prefixStmt.setContext(prefixContext);
            newImport.addChild(prefixStmt);
            //newImport.init();
            //newImport.build();
            candidate = newImport;
        }
        else {
            YangContext newIncludeContext = new YangContext(curModule.getContext());
            Include newInclude = new IncludeImpl(linkageInfo.getModuleId().getModuleName());
            newInclude.setContext(newIncludeContext);
            //newInclude.init();
            //newInclude.build();
            candidate = newInclude;
        }
        TransformerResult transformerResult = new TransformerResult(TransformerType.ADD,curModule,new ArrayList<>());
        transformerResult.addStatement(null,candidate);
        return transformerResult;
    }
    private static List getUniqueList(List original){
        List list = new ArrayList<>();
        for(Object item :original){
            if(list.contains(item)){
                continue;
            }
            list.add(item);
        }
        return list;
    }

    private static List<TransformerResult> transformLinkage(Module curModule,YangStatement statement){
        Module oriModule = statement.getContext().getCurModule();
        List<TransformerResult> transformerResults = new ArrayList<>();
        if(oriModule != curModule){
            if(statement instanceof IdentifierRef){
                LinkageInfo linkageInfo = genLinkageInfoForIdentifierRef(curModule, (IdentifierRef) statement);
                if(linkageInfo != null){
                    TransformerResult transformerResult = getTransformerResultFromLinkage(linkageInfo,curModule);
                    transformerResults.add(transformerResult);
                }
            } else if (statement instanceof XPathSupport){
                transformerResults.addAll(transformXPathSupport(curModule, (XPathSupport) statement));
                //TODO fix bug  reformat xpath expression
            } else if (statement instanceof IfFeature){
                //TODO
                IfFeature ifFeature = (IfFeature) statement;
                IfFeatureExprVisitor ifFeatureExprVisitor = new IfFeatureExprVisitor();
                List<String> prefixes = ifFeatureExprVisitor.visit(ifFeature.getIfFeatureExpr());
                List list = getUniqueList(prefixes);
                for(Object o:list){
                    String prefix = (String) o;
                    ModuleId moduleId = ifFeature.getContext().getCurModule().getPrefixes().get(prefix);
                    if(!curModule.getModuleId().equals(moduleId) && curModule.getImportByPrefix(prefix) == null){
                        LinkageInfo linkageInfo = new LinkageInfo(LinkageType.IMPORT,moduleId);
                        linkageInfo.setPrefix(prefix);
                        transformerResults.add(getTransformerResultFromLinkage(linkageInfo,curModule));
                    }
                }
            }

        }
        transformerResults.addAll(procSubElement(curModule,statement,TransformerPhase.PHASE_LINKAGE));
        return transformerResults;
    }

    private static YangStatement getAncestorStatement(YangStatement statement){
        YangStatement ancestor = statement;
        YangStatement parent = statement.getParentStatement();
        while (parent != null){
            if(parent instanceof Case){
                Case c = (Case) parent;
                if(c.isShortCase()){
                    parent = c.getParent();
                }
            }
            ancestor = parent;
            parent = ancestor.getParentStatement();
        }
        return ancestor;
    }
    private static boolean isEmptyStatement(YangStatement statement){
        for(YangElement subElement:statement.getSubElements()){
            if(subElement instanceof YangStatement){
                return false;
            }
        }
        return true;
    }

    private static List<TransformerResult> transformUnused(Module curModule,YangStatement statement){
        List<TransformerResult> transformerResults = new ArrayList<>();
        if(statement instanceof Referencable){
            if(isUnUsed(statement)){
                TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE, statement.getParentStatement(), new ArrayList<>());
                transformerResult.addStatement(null, statement);
                transformerResults.add(transformerResult);
                return transformerResults;
            }
        } else if(statement instanceof Augment || statement instanceof Input || statement instanceof Output){
            SchemaNodeContainer schemaNodeContainer = (SchemaNodeContainer) statement;
            if(schemaNodeContainer.getEffectiveSchemaNodeChildren().size() == 0){
                TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE,statement.getParentStatement(),new ArrayList<>());
                transformerResult.addStatement(null,statement);
                transformerResults.add(transformerResult);
                return transformerResults;
            }
        }
        transformerResults.addAll(procSubElement(curModule,statement,TransformerPhase.PHASE_UNUSED));
        return transformerResults;
    }

    private static List<String> lookupPrefixes(Module curModule,YangStatement statement){
        List<String> transformerResults = new ArrayList<>();
        if(statement instanceof IdentifierRef){
            FName fName = new FName(statement.getArgStr());
            if(fName.getPrefix() != null){
                transformerResults.add(fName.getPrefix());
            }
        }  else if(statement instanceof IfFeature){
            IfFeature ifFeature = (IfFeature) statement;
            IfFeature.IfFeatureExpr ifFeatureExpr = ifFeature.getIfFeatureExpr();
            transformerResults.addAll(new IfFeatureExprVisitor().visit(ifFeatureExpr));
        } else if(statement instanceof XPathSupport){
            XPathSupport xPathSupport = (XPathSupport) statement;
            YangXPathPrefixVisitor pathPrefixVisitor = new YangXPathPrefixVisitor((XPathSupport) statement,curModule);
            transformerResults.addAll(pathPrefixVisitor.visit(xPathSupport.getXPathExpression().getRootExpr(),xPathSupport));
        } else if (statement instanceof YangUnknown){
            YangUnknown yangUnknown = (YangUnknown) statement;
            FName fName = new FName(yangUnknown.getKeyword());
            if(fName.getPrefix() != null){
                transformerResults.add(fName.getPrefix());
            }
        } else if (statement instanceof DataNodeModifier){
            DataNodeModifier dataNodeModifier = (DataNodeModifier) statement;
            List<QName> steps = dataNodeModifier.getTargetPath().getPath();
            for(QName step:steps){
                if(step.getPrefix() != null){
                    transformerResults.add(step.getPrefix());
                }
            }
        } else if (statement instanceof Default){
            FName fName = new FName(statement.getArgStr());
            if(fName.getPrefix() != null){
                transformerResults.add(fName.getPrefix());
            }
        }
        if(statement.getSubElements().size() > 0){
            List<String> sonTransformerResults = new ArrayList<>();
            for(YangElement subElement: statement.getSubElements()){
                if(subElement instanceof YangStatement){
                    List<String> sonTransformerResult = lookupPrefixes(curModule,(YangStatement) subElement);
                    sonTransformerResults.addAll(sonTransformerResult);
                }
            }
            transformerResults.addAll(sonTransformerResults);
        }
        return transformerResults;
    }
    private static List<TransformerResult> procSchemaNodeChildren(SchemaNodeContainer statement){
        List<TransformerResult> transformerResults = new ArrayList<>();
        if(statement.getSchemaNodeChildren().size() > 0){
            for(SchemaNode schemaNode: statement.getSchemaNodeChildren()){
                List<TransformerResult> sonTransformerResult = transformExpand(schemaNode);
                transformerResults.addAll(sonTransformerResult);
            }
        }
        return transformerResults;
    }

    private static List<TransformerResult> procSubElement(Module curModule, YangStatement statement,TransformerPhase phase){
        List<TransformerResult> transformerResults = new ArrayList<>();
        if(statement.getSubElements().size() > 0){
            List<TransformerResult> sonTransformerResults = new ArrayList<>();
            for(YangElement subElement: statement.getSubElements()){
                if(subElement instanceof YangStatement){
                    List<TransformerResult> sonTransformerResult = transform(curModule,(YangStatement) subElement,phase);
                    sonTransformerResults.addAll(sonTransformerResult);
                }
            }
            transformerResults.addAll(sonTransformerResults);
        }
        return transformerResults;
    }
    public static List<TransformerResult> procUnusedLinkage(Module curModule){
        List<TransformerResult> transformerResults = new ArrayList<>();
        List<String> prefixes = lookupPrefixes(curModule,curModule);
        List<Import> imports = curModule.getImports();
        for(Import im:imports){
            if(!prefixes.contains(im.getPrefix().getArgStr())){
                TransformerResult transformerResult = new TransformerResult(TransformerType.DELETE,curModule,new ArrayList<>());
                transformerResult.addStatement(null,im);
                transformerResults.add(transformerResult);
            }
        }
        return transformerResults;
    }
    public static List<TransformerResult> transform(Module curModule,YangStatement statement,TransformerPhase phase){
        switch (phase){
            case PHASE_STATEMENT:{
                return transformStmt(curModule,statement);
            }
            case PHASE_LINKAGE:{
                return transformLinkage(curModule,statement);
            }
            case PHASE_UNUSED:{
                return transformUnused(curModule,statement);
            }
        }
        return null;
    }

    private static boolean checkChild(YangStatement statement,YangStatement subStatement){
        if(statement.getContext() == null){
            return true;
        }
        if(statement.getSubStatement(subStatement.getYangKeyword(), subStatement.getArgStr())!= null){
            return false;
        }
        YangSpecification yangSpecification = statement.getContext().getYangSpecification();
        if(yangSpecification == null){
            return true;
        }
        YangStatementDef statementDef = yangSpecification.getStatementDef(statement.getYangKeyword());
        if(statementDef == null){
            return true;
        }
        if(subStatement instanceof DefaultYangUnknown){
            return true;
        }
        Cardinality cardinality = statementDef.getSubStatementCardinality(subStatement.getYangKeyword());
        if(cardinality == null){
            return false;
        }
        if(cardinality.isUnbounded()){
            return true;
        }
        List<YangStatement> matched = statement.getSubStatement(subStatement.getYangKeyword());
        if((matched.size() +1) > cardinality.getMaxElements()){
            return false;
        }
        return true;
    }
    private static void insert(YangStatement target, YangStatement pre,YangStatement candidate){
        if(!checkChild(target,candidate)){
            return;
        }

        //lookup the insert position

        int pos = -1;
        for (int j = 0; j < target.getSubElements().size(); j++) {
            YangElement subElement = target.getSubElements().get(j);
            if (subElement instanceof YangStatement) {
                YangStatement subStatement = (YangStatement) subElement;
                if(pre != null){
                    if(subStatement == pre){
                        pos =  j+1;
                        break;
                    }
                }
                else {
                    if (subStatement.getYangKeyword().equals(candidate.getYangKeyword())) {
                        pos = j + 1;
                    }
                }
            }
        }
        YangStatement origParent = candidate.getParentStatement();
        if(pos != -1){
            target.addChild(pos,candidate);
        }
        else {
            target.addChild(candidate);
        }
    }

    private static void procTransformerResult(List<TransformerResult> transformerResults){
        //add
        for(TransformerResult transformerResult:transformerResults) {
            YangStatement target = transformerResult.getTarget();
            if (target == null) {
                continue;
            }
            if (transformerResult.getType() == TransformerType.ADD) {
                for (TransformerStatement candidate : transformerResult.getStatements()) {
                    insert(target,candidate.getPre(), candidate.getStatement());
                }
            }
        }
        //delete
        for(TransformerResult transformerResult:transformerResults) {
            YangStatement target = transformerResult.getTarget();
            if (target == null) {
                continue;
            }
            if (transformerResult.getType() == TransformerType.DELETE) {
                for(TransformerStatement candidate:transformerResult.getStatements()){
                    target.removeChild(candidate.getStatement());
                }
            }
        }
    }

    private static boolean isUnUsedModule(Module module){
        if(module instanceof SubModule){
            return isUnUsedModule(module.getMainModule());
        }
        //if no any modules depend on this module,check whether it has data definitions, if it has no,it's unuseful
        if(!module.getSchemaNodeChildren().isEmpty()){
            return false;
        }
        if(!module.getContext().getIdentityCache().isEmpty()){
            return false;
        }
        if(!module.getAugments().isEmpty()){
            return false;
        }
        for(Include include:module.getIncludes()){
            SubModule sb = include.getInclude().get();
            if(!sb.getAugments().isEmpty()){
                return false;
            }
        }
        if(!module.getDeviations().isEmpty()){
            return false;
        }
        for(Include include:module.getIncludes()){
            SubModule sb = include.getInclude().get();
            if(!sb.getDeviations().isEmpty()){
                return false;
            }
        }
        for(Module dependent: module.getDependentBys()){
            if(!isUnUsedModule(dependent)){
                return false;
            }
        }
        return true;
    }

    private static void transformUnUsed(YangSchemaContext schemaContext){
        Iterator<Map.Entry<String, List<YangElement>>> it = schemaContext.getParseResult().entrySet().iterator();
        List<String> unUsedModules = new ArrayList<>();
        while(it.hasNext()){
            Map.Entry<String, List<YangElement>> entry = it.next();
            List<YangElement> elements = entry.getValue();
            if(elements == null || elements.size() ==0){
                continue;
            }
            int size = elements.size();

            for(int i = 0; i < size;i++){
                YangElement element = elements.get(i);
                if(element instanceof YangStatement){
                    assert (element instanceof Module);
                    Module curModule = (Module) element;
                    //phase_unused
                    List<TransformerResult> transformerResults = transform(curModule,curModule,TransformerPhase.PHASE_UNUSED);
                    procTransformerResult(transformerResults);
                    if(isUnUsedModule(curModule)){
                        unUsedModules.add(entry.getKey());
                        schemaContext.removeModule(curModule.getModuleId());
                        for(Import im:curModule.getImports()){
                            if(im.getImport().isPresent()){
                                MainModule importedModule = im.getImport().get();
                                importedModule.removeDependentBy(curModule);
                            }
                        }
                    }
                }
            }
        }
        for(String unUsedModule:unUsedModules){
            schemaContext.getParseResult().remove(unUsedModule);
        }
    }
    private static void transformUnUsedLinkage(YangSchemaContext schemaContext){
        Iterator<Map.Entry<String, List<YangElement>>> it = schemaContext.getParseResult().entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, List<YangElement>> entry = it.next();
            List<YangElement> elements = entry.getValue();
            if(elements == null || elements.size() ==0){
                continue;
            }
            int size = elements.size();

            for(int i = 0; i < size;i++){
                YangElement element = elements.get(i);
                if(element instanceof YangStatement){
                    assert (element instanceof Module);
                    Module curModule = (Module) element;
                    //delete unused linkage
                    List<TransformerResult> transformerResults = procUnusedLinkage(curModule);
                    procTransformerResult(transformerResults);
                }

            }
        }
    }
    private static void transformStmt(YangSchemaContext schemaContext){
        Iterator<Map.Entry<String, List<YangElement>>> it = schemaContext.getParseResult().entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, List<YangElement>> entry = it.next();
            List<YangElement> elements = entry.getValue();
            if(elements == null || elements.size() ==0){
                continue;
            }
            int size = elements.size();

            for(int i = 0; i < size;i++){
                YangElement element = elements.get(i);
                if(element instanceof YangStatement){
                    assert (element instanceof Module);
                    Module curModule = (Module) element;
                    //phase_stmt
                    List<TransformerResult> transformerResults = transform(curModule,curModule,TransformerPhase.PHASE_STATEMENT);
                    procTransformerResult(transformerResults);
                }

            }
        }
    }
    private static void transformLinkage(YangSchemaContext schemaContext){
        Iterator<Map.Entry<String, List<YangElement>>> it = schemaContext.getParseResult().entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, List<YangElement>> entry = it.next();
            List<YangElement> elements = entry.getValue();
            if(elements == null || elements.size() ==0){
                continue;
            }
            int size = elements.size();

            for(int i = 0; i < size;i++){
                YangElement element = elements.get(i);
                if(element instanceof YangStatement){
                    assert (element instanceof Module);
                    Module curModule = (Module) element;
                    //phase_stmt
                    List<TransformerResult> transformerResults  = transform(curModule,curModule,TransformerPhase.PHASE_LINKAGE);
                    procTransformerResult(transformerResults);
                }

            }
        }
    }
    public static void transform(YangSchemaContext schemaContext){
        List<SchemaNode> schemaNodes = schemaContext.getSchemaNodeChildren();
        List<TransformerResult> transformerResults = new ArrayList<>();
        for(SchemaNode schemaNode:schemaNodes){
            transformerResults.addAll(transformExpand(schemaNode));
        }
        procTransformerResult(transformerResults);
        transformStmt(schemaContext);
        transformLinkage(schemaContext);
    }

    /**
     * usage: -src {src-dir} -out {out-dir} [-no-deviations | -reserve-deviations]
     *             [ -reserve {--only-huawei-native
     *             | {--module | --namespace} {match {regex} [except {regex}...]}...} ]
     * @param args
     */
    public static void main(String[] args){
        String yangDir = null;
        String outDir = null;
        boolean hasTailor = false;
        TailorType tailorType = null;
        boolean onlyHuaweiNative = false;
        Stack<MatchRule> matchRules = new Stack<>();
        boolean paraError= false;
        boolean inExcept = false;
        boolean noDeviations = true;
        for(int i = 0;i< args.length;i++){
            if(args[i].equals("-src")){
                yangDir = args[i+1];
            } else if(args[i].equals("-out")){
                outDir = args[i+1];
            } else if(args[i].equals("-no-deviations")){
                if(!noDeviations){
                    System.out.println("duplicate deviations parameter. It must be " +
                            "one of 'no-deviations' and 'reserve-deviations");
                    paraError = true;
                    break;
                }
                noDeviations = true;
            }else if(args[i].equals("-reserve-deviations")){
                noDeviations = false;
            }
            else if(args[i].equals("--only-huawei-native")){
                if(!hasTailor){
                    paraError = true;
                    System.out.println("no reserve parameter.");
                    break;
                }
                if(tailorType != null){
                    paraError = true;
                    System.out.println("wrong reserve parameter.");
                    break;
                }
                onlyHuaweiNative = true;
            } else if(args[i].equals("-reserve")){
                hasTailor = true;
            } else if(args[i].equals("--module") ){
                if(!hasTailor){
                    paraError = true;
                    System.out.println("no reserve parameter.");
                    break;
                }
                if(onlyHuaweiNative){
                    paraError = true;
                    System.out.println("wrong reserve parameter.");
                    break;
                }
                if(null != tailorType){
                    paraError = true;
                    System.out.println("duplicate reserve type parameter.");
                    break;
                }
                tailorType = TailorType.MODULE;
            } else if(args[i].equals("--namespace")){
                if(!hasTailor){
                    paraError = true;
                    System.out.println("no reserve parameter.");
                    break;
                }
                if(onlyHuaweiNative){
                    paraError = true;
                    System.out.println("wrong reserve parameter.");
                    break;
                }
                if(null != tailorType){
                    paraError = true;
                    System.out.println("duplicate reserve type parameter.");
                    break;
                }
                tailorType = TailorType.NAMESPACE;
            }
            else if(args[i].equals("match")){
                if(!hasTailor){
                    paraError = true;
                    System.out.println("no reserve parameter.");
                    break;
                }
                if(tailorType == null){
                    paraError = true;
                    System.out.println("no reserve type parameter.");
                    break;
                }
                if(inExcept){
                    inExcept = false;
                }
                String matchStr = args[i+1];
                MatchRule matchRule = new MatchRule(matchStr);
                matchRules.push(matchRule);
            } else if(args[i].equals("except")){
                MatchRule matchRule = matchRules.peek();
                if(matchRule == null){
                    paraError = true;
                    System.out.println("no match parameter.");
                }
                inExcept = true;
            } else {
                if(inExcept){
                    MatchRule matchRule = matchRules.peek();
                    matchRule.addExcept(args[i]);
                }
            }
        }

        if(yangDir == null){
            System.out.println("no src yang directory.");
            paraError = true;
        }
        if(outDir == null){
            System.out.println("no output yang directory.");
            paraError = true;
        }

        if(paraError){
            System.out.println("usage: -src {src-dir} -out {out-dir} [no-deviations | reserve-deviations] [ -reserve {--only-huawei-native " +
                    "| {--module | --namespace} {match <regex> [except <regex>...] }...}  ]");
            return;
        }

        File outDirFile = new File(outDir);
        if(!outDirFile.exists()){
            outDirFile.mkdirs();
        }
        try {
            YangSchemaContext schemaContext = YangYinParser.parse(yangDir);
            ValidatorResult validatorResult = schemaContext.validate();
            if(!validatorResult.isOk()){
                System.out.println("[WARNING]failed to parse the source yang files.");
                System.out.println(validatorResult);
            }
            if(noDeviations){
                transform(schemaContext);
                validatorResult = schemaContext.validate();
                // if(!validatorResult.isOk()){
                //     System.out.println("[WARNING]failed to transform the source yang files.");
                //     System.out.println(validatorResult);
                // }
            }

            //System.out.println(validatorResult);
            YangSchemaContext transformedContext = YangStatementRegister.getInstance().getSchemeContextInstance();
            Iterator<Map.Entry<String, List<YangElement>>> it = schemaContext.getParseResult().entrySet().iterator();
            while(it.hasNext()){
                Map.Entry<String, List<YangElement>> entry = it.next();
                List<YangElement> elements = entry.getValue();
                List<YangElement> transformedElements = new ArrayList<>();
                for(YangElement element:elements){
                    if(element instanceof Module){
                        Module module = (Module) element;
                        Module transModule = (Module) module.clone();
                        transformedElements.add(transModule);
                        transformedContext.addModule(transModule);
                    }
                    else {
                        transformedElements.add(element);
                    }
                }
                transformedContext.getParseResult().put(entry.getKey(),transformedElements);
            }
            transformedContext.validate();
            transformUnUsed(transformedContext);
            //transformUnUsedLinkage(transformedContext);
            //validatorResult = transformedContext.validate();
            // System.out.println(validatorResult);
            if(hasTailor){
                if(onlyHuaweiNative){
                    MatchRule nativeRule = new MatchRule("huawei");
                    nativeRule.addExcept("huawei-ietf");
                    nativeRule.addExcept("huawei-openconfig");
                    matchRules.add(nativeRule);
                    MatchRule telemetry = new MatchRule("openconfig-telemetry");
                    MatchRule telemetryExt = new MatchRule("huawei-openconfig-telemetry-ext");
                    matchRules.add(telemetryExt);
                    matchRules.add(telemetry);
                    tailorType = TailorType.MODULE;
                }
                YangTailorRule tailorRule = new YangTailorRule(tailorType,matchRules);
                YangTailor yangTailor = new YangTailor(transformedContext,tailorRule);
                yangTailor.tailor();
            }

            it = transformedContext.getParseResult().entrySet().iterator();
            while(it.hasNext()){
                Map.Entry<String, List<YangElement>> entry = it.next();
                List<YangElement> elements = entry.getValue();
                StringBuffer sb= new StringBuffer();
                for(YangElement element:elements){
                    if(element instanceof YangStatement){
                        YangStatement statement = (YangStatement) element;
                        if(statement.isErrorStatement()){
                            continue;
                        }
                    }
                    String str = YangWriter.toYangString(element,YangFormatter.getPrettyYangFormatter()," ");
                    sb.append(str);
                    sb.append("\n");
                }

                String fileName;
                int index = entry.getKey().lastIndexOf(File.separator);
                if(index < 0){
                    fileName = entry.getKey();
                } else {
                    fileName = entry.getKey().substring(index);
                }


                File outFile = new File(outDir,fileName);
                if(!outFile.exists()){
                    outFile.createNewFile();
                }
                FileUtil.writeUtf8File(sb.toString(),outFile);

            }
            YangSchemaContext outSchemaContext = YangYinParser.parse(outDir);
            validatorResult = outSchemaContext.validate();
            if(!validatorResult.isOk()){
                System.out.println("[WARNING]failed to parse the transformed yang files.");
                System.out.println(validatorResult);
            }
            System.out.println("[INFO] The source yang files have been transformed, the reuslt is in dir:"+ outDir);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (YangParserException e) {
            e.printStackTrace();
        } catch (DocumentException e) {
            e.printStackTrace();
        }

    }
}
