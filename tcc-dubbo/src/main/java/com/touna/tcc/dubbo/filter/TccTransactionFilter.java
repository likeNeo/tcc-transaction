package com.touna.tcc.dubbo.filter;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import com.alibaba.dubbo.common.URL;
import com.touna.tcc.core.log.service.TxChildLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.rpc.*;
import com.touna.tcc.core.Attachment;
import com.touna.tcc.core.TccIllegalOperationException;
import com.touna.tcc.core.TccContext;
import com.touna.tcc.core.TwoPhaseBusinessAction;
import com.touna.tcc.core.transaction.*;

/**
 * Created by chenchaojian on 17/5/25.
 * tcc chain logger
 */
@Activate(group = {Constants.CONSUMER})
public class TccTransactionFilter implements Filter {
    private static final Logger logger = LoggerFactory.getLogger(TccTransactionFilter.class);

    private AbstractTransactionManager tccTransaction;

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {

        boolean isTccTry = false;
        String clsNameRef = null;
        String tryMethodNameRef = null;
        /**
         * both success try and fail try need to log. both success try and fail try need to rollback while exception
         * occur(timeout or network may cause try fail,but provider may success executed).
         */
        try {

            Transaction txObject = TransactionSynchronizationManager.getResource();
            if (txObject != null) {//tcc transaction chain flag
                String xid = txObject.getXid();
                Class cls = invoker.getInterface();
                String methodName = invocation.getMethodName();

                Class[] paramsTypes = invocation.getParameterTypes();
                Method method = cls.getDeclaredMethod(methodName, paramsTypes);
                TwoPhaseBusinessAction annotation = method
                        .getAnnotation(TwoPhaseBusinessAction.class);

                // dubbo filter only log try data. commit and rollback data logged in TransactionManager
                if (annotation != null) {

                    isTccTry = true;
                    clsNameRef = cls.getName();
                    tryMethodNameRef = methodName;
                    String commitMethod = annotation.commitMethod();
                    String rollbackMethod = annotation.rollbackMethod();

                    //forbid dubbo failover cluster call provider repeatly
                    if (TransactionSynchronizationManager.getInvokeMetadata(cls.getName(), methodName) == null) {

                        //commit and rollback method
                        Class[] cmtRbParamsTypes = new Class[2];
                        cmtRbParamsTypes[0] = String.class;
                        cmtRbParamsTypes[1] = TccContext.class;

                        //check whether commitMethod and rollbakMethod is in specifications
                        checkMethodSignature(cls, cmtRbParamsTypes, commitMethod, rollbackMethod);

                        Object[] arguments = invocation.getArguments();
                        Object[] cmtRbArguments = makeCmtRbArguments(method, arguments, xid);

                        int index = TransactionSynchronizationManager.newChildTxIndex();
                        String cXid = XidGenerator.newCXid(xid, index);

                        TCCInvokeMetadata invokeMetadata = new TCCInvokeMetadata();
                        invokeMetadata.setClsName(cls.getName());
                        invokeMetadata.setCommitMethod(commitMethod);
                        invokeMetadata.setRollbackMethod(rollbackMethod);

                        invokeMetadata.setParamsTypes(cmtRbParamsTypes);


                        invokeMetadata.setParamValues(cmtRbArguments);
                        invokeMetadata.setcXid(cXid);
                        invokeMetadata.setIndex(index);
                        invokeMetadata.setXid(xid);

                        TransactionSynchronizationManager.setTCCInvokeMetadata(cls.getName(), methodName, invokeMetadata);
                    }

                }
            }

            Result result = invoker.invoke(invocation);
            if (isTccTry) {
                if (result.getException() == null) {
                    log(TransactionSynchronizationManager.getInvokeMetadata(clsNameRef, tryMethodNameRef), true);
                } else {
                    log(TransactionSynchronizationManager.getInvokeMetadata(clsNameRef, tryMethodNameRef), false);
                }
            }


            return result;

        }
        catch (TccIllegalOperationException ex) {
            //not need to make consumer retry
            throw new RpcException(RpcException.BIZ_EXCEPTION,ex);
        }
        catch (Throwable ex) {
            if (isTccTry) {
                log(TransactionSynchronizationManager.getInvokeMetadata(clsNameRef, tryMethodNameRef), false);
            }

            throw new RpcException(ex);
        }

    }

    private void log(TCCInvokeMetadata invokeMetadata,boolean successful){
        TxChildLogService txChildLogService = tccTransaction.getTxChildLogService();
        int index = invokeMetadata.getIndex();
        String xid = invokeMetadata.getXid();
        String cXid = invokeMetadata.getcXid();
        String clsName = invokeMetadata.getClsName();
        String commitMethod = invokeMetadata.getCommitMethod();
        String rollbackMethod = invokeMetadata.getRollbackMethod();
        Class[]paramsTypes = invokeMetadata.getParamsTypes();
        Object[]paramValues = invokeMetadata.getParamValues();
        //need to log,
        URL url = RpcContext.getContext().getUrl();
        String version = url.getParameter(Constants.VERSION_KEY);

        if(successful) {
            //may insert or update
            txChildLogService.trySuccess(index, xid, cXid,  clsName,  commitMethod,
                     rollbackMethod, paramsTypes, paramValues,version);
        }
        else {
            //may insert or update
            txChildLogService.tryFail(index, xid, cXid, clsName, commitMethod,
                    rollbackMethod, paramsTypes, paramValues,version);
        }
    }


    /**
     * make commit and rollback arguments
     *
     * @param method
     * @param arguments
     * @return
     */
    private Object[] makeCmtRbArguments(Method method, Object[] arguments, String xid) {
        Object objs[] = new Object[2];
        objs[0] = xid;

        TccContext tccContext = new TccContext();

        int i = 0;
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof Attachment) {
                    Attachment attach = (Attachment) annotation;
                    tccContext.setAttachment(attach.key(), arguments[i]);
                }
            }

            i++;
        }

        objs[1] = tccContext;
        return objs;
    }


    private void checkMethodSignature(Class cls, Class[] paramsType, String commitMethod, String rollbackMethod) {
        try {
            Method methodCmt = cls.getDeclaredMethod(commitMethod, paramsType);
            Type returnType = methodCmt.getGenericReturnType();
            Class returnCls = (Class)returnType;
            if(!returnCls.isAssignableFrom(java.lang.Boolean.class)){
                throw new TccIllegalOperationException("commit method " + cls + "." + commitMethod + " return type must be Boolean");
            }


        } catch (NoSuchMethodException e) {
            throw new TccIllegalOperationException("commit method " + cls + "." + commitMethod + " must signature with String and TccContext");
        }

        try {
            Method methodRollback = cls.getDeclaredMethod(rollbackMethod, paramsType);
            Type returnTypeRollback = methodRollback.getGenericReturnType();
            Class returnClsRollback = (Class)returnTypeRollback;
            if(!returnClsRollback.isAssignableFrom(java.lang.Boolean.class)){
                throw new TccIllegalOperationException("commit method " + cls + "." + commitMethod + " return type must be Boolean");
            }
        } catch (NoSuchMethodException e) {
            throw new TccIllegalOperationException("rollback method " + cls + "." + rollbackMethod + " must signature with String and TccContext");
        }

    }

    public AbstractTransactionManager getTccTransaction() {
        return tccTransaction;
    }

    public void setTccTransaction(AbstractTransactionManager tccTransaction) {
        this.tccTransaction = tccTransaction;
    }
}
